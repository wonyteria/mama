package kr.mom.probe.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single normalization boundary between a raw notification payload and
 * a stored [ProbeRecord]. Production capture ([ProbeRepository.capture])
 * and the debug/test synthetic-notification seam both flow through this —
 * bounding, truncation flags, raw hashing and revision identity are never
 * duplicated or fabricated downstream.
 */
object NotificationRecordAssembler {
    const val MAX_FIELD = 32_768
    const val MAX_LINES = 100

    /** Raw, unbounded notification fields exactly as the OS/app sent them. */
    data class Input(
        val packageName: String,
        val key: String,
        val postTime: Long,
        val notificationId: Int,
        val ongoing: Boolean,
        val groupSummary: Boolean,
        val appLabel: String,
        val category: String? = null,
        val channelId: String? = null,
        val title: String = "",
        val text: String = "",
        val bigText: String = "",
        val textLines: List<String> = emptyList(),
        val subText: String? = null,
        val summaryText: String? = null,
    )

    fun assemble(input: Input, receivedAt: Long): ProbeRecord {
        var truncated = false
        fun bounded(value: String): String {
            if (value.length > MAX_FIELD) truncated = true
            return value.take(MAX_FIELD)
        }
        val lines = input.textLines.let {
            if (it.size > MAX_LINES) truncated = true
            it.take(MAX_LINES).map { line -> bounded(line) }
        }
        // Hash the actual payload, including omitted suffixes, so updates beyond the storage limit
        // remain distinct revisions rather than being silently mistaken for duplicate callbacks.
        val rawHash = ProbeRules.digest(JSONObject().put("title", input.title)
            .put("text", input.text).put("bigText", input.bigText)
            .put("lines", JSONArray(input.textLines))
            .put("subText", input.subText.orEmpty())
            .put("summaryText", input.summaryText.orEmpty()).toString())
        return ProbeRecord(
            id = ProbeRules.revisionId(input.packageName, input.key, input.postTime, rawHash),
            packageName = input.packageName,
            appLabel = input.appLabel,
            postedAt = input.postTime,
            receivedAt = receivedAt,
            title = bounded(input.title),
            text = bounded(input.text),
            bigText = bounded(input.bigText),
            textLines = lines,
            subText = input.subText?.let(::bounded)?.ifEmpty { null },
            summaryText = input.summaryText?.let(::bounded)?.ifEmpty { null },
            category = input.category,
            channelId = input.channelId,
            notificationId = input.notificationId,
            notificationKey = input.key,
            isOngoing = input.ongoing,
            isGroupSummary = input.groupSummary,
            rawHash = rawHash,
            truncated = truncated,
        )
    }
}
