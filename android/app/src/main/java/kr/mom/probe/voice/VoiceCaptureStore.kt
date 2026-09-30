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

class VoiceCaptureStore private constructor(context: Context) : CaptureJournalSink {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("voice_captures", Context.MODE_PRIVATE)
    private val mutableRecords = MutableStateFlow<List<VoiceCaptureRecord>>(emptyList())
    val records = mutableRecords.asStateFlow()
    private var loaded = false
    private var journalsLoaded = false
    private val journals = LinkedHashMap<String, CaptureWriteJournal>()

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

    // --- Durable write journal (CaptureJournalSink) ------------------

    @Synchronized
    override fun journalFor(captureId: String): CaptureWriteJournal {
        loadJournals()
        return journals[captureId] ?: CaptureWriteJournal(captureId)
    }

    @Synchronized
    override fun saveJournal(journal: CaptureWriteJournal) {
        loadJournals()
        journals[journal.captureId] = journal
        while (journals.size > MAX_RECORDS) journals.remove(journals.keys.first())
        val array = JSONArray()
        journals.values.forEach { array.put(encodeJournal(it)) }
        val bytes = ProbeCrypto().encrypt(array.toString(), "voice_journals")
        if (!preferences.edit().putString("journals", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            throw java.io.IOException("캡처 저장 진행을 기록하지 못했어요.")
        }
    }

    private fun loadJournals() {
        if (journalsLoaded) return
        val encoded = preferences.getString("journals", null)
        if (encoded != null) {
            val array = JSONArray(ProbeCrypto().decrypt(Base64.decode(encoded, Base64.NO_WRAP), "voice_journals"))
            for (i in 0 until array.length()) {
                val journal = decodeJournal(array.getJSONObject(i))
                journals[journal.captureId] = journal
            }
        }
        journalsLoaded = true
    }

    private fun encodeJournal(journal: CaptureWriteJournal): JSONObject = JSONObject()
        .put("captureId", journal.captureId)
        .put("completedWrites", JSONArray(journal.completedWrites.toList()))
        .put("writtenTaskIds", JSONObject(journal.writtenTaskIds.mapKeys { it.key.toString() }))
        .put("recordWritten", journal.recordWritten)
        .put("complete", journal.complete)

    private fun decodeJournal(json: JSONObject): CaptureWriteJournal {
        val ids = json.optJSONObject("writtenTaskIds")
        return CaptureWriteJournal(
            captureId = json.getString("captureId"),
            completedWrites = json.optJSONArray("completedWrites")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }.toSet()
            } ?: emptySet(),
            writtenTaskIds = ids?.let { obj ->
                obj.keys().asSequence().mapNotNull { key -> key.toIntOrNull()?.let { it to obj.getString(key) } }.toMap()
            } ?: emptyMap(),
            recordWritten = json.optBoolean("recordWritten"),
            complete = json.optBoolean("complete"),
        )
    }

    @Synchronized
    private fun clear() {
        loaded = false
        journalsLoaded = false
        journals.clear()
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
