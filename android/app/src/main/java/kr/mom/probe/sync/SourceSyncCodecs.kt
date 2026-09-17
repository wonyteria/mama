package kr.mom.probe.sync

import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.SchoolLevel
import org.json.JSONArray
import org.json.JSONObject

fun encodeRecordSourceMetadata(value: RecordSourceMetadata): JSONObject = JSONObject()
    .put("kind", value.kind.name)
    .put("sourceId", value.sourceId)
    .put("itemId", value.itemId)
    .put("revisionHash", value.revisionHash)
    .put("connectionGeneration", value.connectionGeneration)
    .put("authorizationToken", value.authorizationToken)
    .put("origin", encodeOrigin(value.origin))
    .put("contentState", value.contentState.name)
    .put("obligation", value.obligation.name)
    .put("audienceFacts", JSONArray(value.audienceFacts.map(::encodeAudienceFact)))
    .put("dateFacts", JSONArray(value.dateFacts.map(::encodeDateFact)))
    .put("attachments", JSONArray(value.attachments.map(::encodeAttachment)))
    .put("evidence", JSONArray(value.evidence.map(::encodeEvidence)))
    .put("issues", JSONArray(value.issues.map(::encodeIssue)))
    .put("firstSeenAt", value.firstSeenAt)
    .put("lastFetchedAt", value.lastFetchedAt)
    .put("publishedAt", value.publishedAt ?: JSONObject.NULL)

fun decodeRecordSourceMetadata(json: JSONObject): RecordSourceMetadata = RecordSourceMetadata(
    kind = enumValue(json.optString("kind"), SourceKind.ANDROID_NOTIFICATION),
    sourceId = json.getString("sourceId"),
    itemId = json.getString("itemId"),
    revisionHash = json.getString("revisionHash"),
    connectionGeneration = json.optLong("connectionGeneration", 0L),
    authorizationToken = json.optString("authorizationToken"),
    origin = decodeOrigin(json.getJSONObject("origin")),
    contentState = enumValue(json.optString("contentState"), NoticeContentState.PARTIAL_EXTRACTION),
    obligation = enumValue(json.optString("obligation"), NoticeObligation.INFORMATIONAL),
    audienceFacts = json.optJSONArray("audienceFacts").toList(::decodeAudienceFact),
    dateFacts = json.optJSONArray("dateFacts").toList(::decodeDateFact),
    attachments = json.optJSONArray("attachments").toList(::decodeAttachment),
    evidence = json.optJSONArray("evidence").toList(::decodeEvidence),
    issues = json.optJSONArray("issues").toList(::decodeIssue),
    firstSeenAt = json.optLong("firstSeenAt"),
    lastFetchedAt = json.optLong("lastFetchedAt"),
    publishedAt = json.optLongOrNull("publishedAt"),
)

fun encodeSourceSnapshot(value: SourceSyncSnapshot): JSONObject = JSONObject()
    .put("sourceId", value.sourceId)
    .put("kind", value.kind.name)
    .put("authorizationToken", value.authorizationToken)
    .put("status", value.status.name)
    .put("lastAttemptAt", value.lastAttemptAt ?: JSONObject.NULL)
    .put("lastSuccessAt", value.lastSuccessAt ?: JSONObject.NULL)
    .put("lastCompleteAt", value.lastCompleteAt ?: JSONObject.NULL)
    .put("coverageWindow", value.coverageWindow?.let(::encodeCoverageWindow) ?: JSONObject.NULL)
    .put("seenCount", value.seenCount)
    .put("matchedCount", value.matchedCount)
    .put("storedCount", value.storedCount)
    .put("attachmentState", value.attachmentState.name)
    .put("reasonCode", value.reasonCode?.name ?: JSONObject.NULL)
    .put("message", value.message ?: JSONObject.NULL)
    .put("checkpoint", value.checkpoint?.let(::encodeCheckpoint) ?: JSONObject.NULL)
    .put("sourceGeneration", value.sourceGeneration)

fun decodeSourceSnapshot(json: JSONObject): SourceSyncSnapshot = SourceSyncSnapshot(
    sourceId = json.getString("sourceId"),
    kind = enumValue(json.optString("kind"), SourceKind.ANDROID_NOTIFICATION),
    authorizationToken = json.optString("authorizationToken"),
    status = enumValue(json.optString("status"), SourceSyncStatus.NEVER),
    lastAttemptAt = json.optLongOrNull("lastAttemptAt"),
    lastSuccessAt = json.optLongOrNull("lastSuccessAt"),
    lastCompleteAt = json.optLongOrNull("lastCompleteAt"),
    coverageWindow = json.optJSONObject("coverageWindow")?.let(::decodeCoverageWindow),
    seenCount = json.optInt("seenCount"),
    matchedCount = json.optInt("matchedCount"),
    storedCount = json.optInt("storedCount"),
    attachmentState = enumValue(json.optString("attachmentState"), AttachmentFetchState.NONE),
    reasonCode = json.nullableString("reasonCode")?.let { enumValue(it, SourceIssueCode.PARSE_ERROR) },
    message = json.nullableString("message"),
    checkpoint = json.optJSONObject("checkpoint")?.let(::decodeCheckpoint),
    sourceGeneration = json.optLong("sourceGeneration", 0L),
)

fun encodeCheckpoint(value: SourceCheckpoint): JSONObject = JSONObject()
    .put("cursor", value.cursor ?: JSONObject.NULL)
    .put("coverageWindow", value.coverageWindow?.let(::encodeCoverageWindow) ?: JSONObject.NULL)
    .put("lastFetchedAt", value.lastFetchedAt ?: JSONObject.NULL)
    .put("sourceGeneration", value.sourceGeneration)
    .put("itemRevisionHashes", JSONObject(value.itemRevisionHashes))

fun decodeCheckpoint(json: JSONObject): SourceCheckpoint {
    val hashes = json.optJSONObject("itemRevisionHashes")
    return SourceCheckpoint(
        cursor = json.nullableString("cursor"),
        coverageWindow = json.optJSONObject("coverageWindow")?.let(::decodeCoverageWindow),
        lastFetchedAt = json.optLongOrNull("lastFetchedAt"),
        sourceGeneration = json.optLong("sourceGeneration", 0L),
        itemRevisionHashes = hashes?.keys()?.asSequence()?.associateWith { hashes.optString(it) }.orEmpty(),
    )
}

fun encodeCoverageWindow(value: SourceCoverageWindow): JSONObject = JSONObject()
    .put("fromDateIso", value.fromDateIso ?: JSONObject.NULL)
    .put("toDateIso", value.toDateIso ?: JSONObject.NULL)
    .put("pageStart", value.pageStart ?: JSONObject.NULL)
    .put("pageEnd", value.pageEnd ?: JSONObject.NULL)
    .put("totalCount", value.totalCount ?: JSONObject.NULL)
    .put("complete", value.complete)

fun decodeCoverageWindow(json: JSONObject): SourceCoverageWindow = SourceCoverageWindow(
    fromDateIso = json.nullableString("fromDateIso"),
    toDateIso = json.nullableString("toDateIso"),
    pageStart = json.optIntOrNull("pageStart"),
    pageEnd = json.optIntOrNull("pageEnd"),
    totalCount = json.optIntOrNull("totalCount"),
    complete = json.optBoolean("complete"),
)

private fun encodeOrigin(value: SourceOrigin): JSONObject = JSONObject()
    .put("canonicalUrl", value.canonicalUrl)
    .put("host", value.host)
    .put("boardId", value.boardId ?: JSONObject.NULL)
    .put("rawId", value.rawId ?: JSONObject.NULL)

private fun decodeOrigin(json: JSONObject): SourceOrigin = SourceOrigin(
    canonicalUrl = json.getString("canonicalUrl"),
    host = json.getString("host"),
    boardId = json.nullableString("boardId"),
    rawId = json.nullableString("rawId"),
)

private fun encodeEvidence(value: SourceEvidence): JSONObject = JSONObject()
    .put("label", value.label)
    .put("value", value.value)

private fun decodeEvidence(json: JSONObject): SourceEvidence = SourceEvidence(
    label = json.optString("label"),
    value = json.optString("value"),
)

private fun encodeIssue(value: SourceIssue): JSONObject = JSONObject()
    .put("code", value.code.name)
    .put("message", value.message)
    .put("itemId", value.itemId ?: JSONObject.NULL)
    .put("recoverable", value.recoverable)

private fun decodeIssue(json: JSONObject): SourceIssue = SourceIssue(
    code = enumValue(json.optString("code"), SourceIssueCode.PARSE_ERROR),
    message = json.optString("message"),
    itemId = json.nullableString("itemId"),
    recoverable = json.optBoolean("recoverable"),
)

private fun encodeAudienceFact(value: SourceAudienceFact): JSONObject = JSONObject()
    .put("applicability", value.applicability.name)
    .put("schoolLevel", value.schoolLevel.name)
    .put("gradeStart", value.gradeStart ?: JSONObject.NULL)
    .put("gradeEnd", value.gradeEnd ?: JSONObject.NULL)
    .put("schoolWide", value.schoolWide)
    .put("evidence", encodeEvidence(value.evidence))

private fun decodeAudienceFact(json: JSONObject): SourceAudienceFact = SourceAudienceFact(
    applicability = enumValue(json.optString("applicability"), NoticeApplicability.UNKNOWN),
    schoolLevel = enumValue(json.optString("schoolLevel"), SchoolLevel.UNKNOWN),
    gradeStart = json.optIntOrNull("gradeStart"),
    gradeEnd = json.optIntOrNull("gradeEnd"),
    schoolWide = json.optBoolean("schoolWide"),
    evidence = json.optJSONObject("evidence")?.let(::decodeEvidence) ?: SourceEvidence("", ""),
)

private fun encodeDateFact(value: SourceDateFact): JSONObject = JSONObject()
    .put("role", value.role.name)
    .put("text", value.text)
    .put("dateIso", value.dateIso ?: JSONObject.NULL)
    .put("preciseAt", value.preciseAt ?: JSONObject.NULL)
    .put("hasExplicitTime", value.hasExplicitTime)
    .put("evidence", value.evidence?.let(::encodeEvidence) ?: JSONObject.NULL)

private fun decodeDateFact(json: JSONObject): SourceDateFact = SourceDateFact(
    role = enumValue(json.optString("role"), NoticeDateRole.UNKNOWN),
    text = json.optString("text"),
    dateIso = json.nullableString("dateIso"),
    preciseAt = json.optLongOrNull("preciseAt"),
    hasExplicitTime = json.optBoolean("hasExplicitTime"),
    evidence = json.optJSONObject("evidence")?.let(::decodeEvidence),
)

private fun encodeAttachment(value: SourceAttachment): JSONObject = JSONObject()
    .put("title", value.title)
    .put("url", value.url)
    .put("contentType", value.contentType ?: JSONObject.NULL)
    .put("state", value.state.name)

private fun decodeAttachment(json: JSONObject): SourceAttachment = SourceAttachment(
    title = json.optString("title"),
    url = json.optString("url"),
    contentType = json.nullableString("contentType"),
    state = enumValue(json.optString("state"), AttachmentFetchState.MISSING),
)

private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
    runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)

private fun <T> JSONArray?.toList(decode: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(decode) }
}

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key).takeIf { it > 0L }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)
