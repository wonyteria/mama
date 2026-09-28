package kr.mom.probe.voice

import android.content.Context
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.mom.probe.data.ProbeCrypto
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.ProbeRules
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable record of a confirmed voice capture. Stores the raw transcript —
 * never audio — the classified intent, and the linked task it produced.
 *
 * Follows the AssistantTaskStore contract: encrypted SharedPreferences +
 * ProbeCrypto (no Room/schema change), consent-gated writes, dropped by
 * delete-all, bounded retention.
 */
data class VoiceCaptureRecord(
    val id: String,
    val rawTranscript: String,
    val intentType: String,
    val createdAt: Long,
    val linkedTaskId: String? = null,
)

class VoiceCaptureStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("voice_captures", Context.MODE_PRIVATE)
    private val mutableRecords = MutableStateFlow<List<VoiceCaptureRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()
    private var loaded = false

    private fun requireConsent() {
        val repository = ProbeRepository.get(app)
        val settings = repository.settings.value
        check(repository.isReady.value && repository.lastError.value == null && settings.consent &&
            settings.consentVersion == ProbeRules.CONSENT_VERSION && settings.onboardingDone) {
            "앱에서 처음 설정을 마쳐주세요."
        }
    }

    @Synchronized
    fun load() {
        requireConsent()
        val encoded = preferences.getString("encrypted", null)
        val now = System.currentTimeMillis()
        val result = if (encoded == null) emptyList() else {
            val array = JSONArray(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), "voice_captures"))
            List(array.length()) { decode(array.getJSONObject(it)) }
                .filter { now - it.createdAt <= ProbeRules.RETENTION_MS }
        }
        mutableRecords.value = result
        loaded = true
    }

    /**
     * Persists a confirmed capture. Idempotent by record id — a retried
     * confirm can never double-write the same capture.
     */
    @Synchronized
    fun save(record: VoiceCaptureRecord): Boolean {
        require(record.rawTranscript.isNotBlank()) { "저장할 발화가 비어 있어요." }
        if (!loaded) load()
        val current = mutableRecords.value
        if (current.any { it.id == record.id }) return true
        val next = (listOf(record) + current).take(MAX_RECORDS)
        val array = JSONArray()
        next.forEach { array.put(encode(it)) }
        val bytes = ProbeCrypto().encrypt(array.toString(), "voice_captures")
        preferences.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit() ||
            return false
        mutableRecords.value = next
        return true
    }

    @Synchronized
    private fun clear() {
        loaded = false
        mutableRecords.value = emptyList()
        if (!preferences.edit().clear().commit()) throw java.io.IOException("음성 기록을 삭제하지 못했어요.")
    }

    private fun encode(record: VoiceCaptureRecord): JSONObject = JSONObject()
        .put("id", record.id)
        .put("rawTranscript", record.rawTranscript)
        .put("intentType", record.intentType)
        .put("createdAt", record.createdAt)
        .put("linkedTaskId", record.linkedTaskId)

    private fun decode(json: JSONObject): VoiceCaptureRecord = VoiceCaptureRecord(
        id = json.optString("id"),
        rawTranscript = json.optString("rawTranscript"),
        intentType = json.optString("intentType"),
        createdAt = json.optLong("createdAt"),
        linkedTaskId = json.optString("linkedTaskId").ifBlank { null },
    )

    companion object {
        private const val MAX_RECORDS = 200

        fun newCaptureId(): String = UUID.randomUUID().toString()

        @Volatile private var instance: VoiceCaptureStore? = null
        fun get(context: Context): VoiceCaptureStore = instance ?: synchronized(this) {
            instance ?: VoiceCaptureStore(context).also { instance = it }
        }
        fun reset(context: Context) = get(context).clear()
    }
}
