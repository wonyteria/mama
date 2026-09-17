package kr.mom.probe.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.DefaultRecordSourcePolicy

data class SourceAgendaItem(
    val record: ProbeRecord,
    val title: String,
    val dateIso: String,
    val sourceLabel: String,
)

object SourceRecordSelectors {
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    fun identity(record: ProbeRecord): String = record.sourceMetadata?.let {
        kr.mom.probe.data.ProbeRules.sourceItemIdentity(it.sourceId, it.itemId)
    } ?: kr.mom.probe.data.ProbeRules.notificationIdentity(record.packageName, record.notificationKey)

    fun activeRecords(
        records: List<ProbeRecord>,
        scopes: List<SourceScope>,
        policy: RecordSourcePolicy = DefaultRecordSourcePolicy,
        now: Long = System.currentTimeMillis(),
    ): List<ProbeRecord> = records.filter { record ->
        val metadata = record.sourceMetadata ?: return@filter true
        scopes.any { scope ->
            policy.decide(metadata, scope, now).state == SourceRecordState.ACTIVE
        }
    }

    fun agenda(
        records: List<ProbeRecord>,
        child: ChildNoticeProfile,
        now: Long = System.currentTimeMillis(),
        daysAhead: Long = 30,
    ): List<SourceAgendaItem> = agendaUnchecked(records, child, now, daysAhead)

    fun agenda(
        records: List<ProbeRecord>,
        child: ChildNoticeProfile,
        scopes: List<SourceScope>,
        policy: RecordSourcePolicy = DefaultRecordSourcePolicy,
        now: Long = System.currentTimeMillis(),
        daysAhead: Long = 30,
    ): List<SourceAgendaItem> = agendaUnchecked(activeRecords(records, scopes, policy, now), child, now, daysAhead)

    private fun agendaUnchecked(
        records: List<ProbeRecord>,
        child: ChildNoticeProfile,
        now: Long,
        daysAhead: Long,
    ): List<SourceAgendaItem> {
        val today = Instant.ofEpochMilli(now).atZone(seoul).toLocalDate()
        val end = today.plusDays(daysAhead)
        return records.flatMap { record ->
            val metadata = record.sourceMetadata ?: return@flatMap emptyList()
            if (!appliesToChild(metadata, child)) return@flatMap emptyList()
            if (metadata.obligation == NoticeObligation.OPTIONAL_OPPORTUNITY) return@flatMap emptyList()
            val incomplete = metadata.contentState !in setOf(NoticeContentState.NOTIFICATION_ONLY, NoticeContentState.VERIFIED)
            if (incomplete && metadata.obligation != NoticeObligation.INFORMATIONAL) return@flatMap emptyList()
            metadata.dateFacts
                .filter { it.role == NoticeDateRole.EVENT }
                .mapNotNull { fact -> fact.dateIso?.let { date -> runCatching { LocalDate.parse(date) }.getOrNull() } }
                .filter { !it.isBefore(today) && !it.isAfter(end) }
                .distinct()
                .map { date ->
                    SourceAgendaItem(
                        record = record,
                        title = record.title.ifBlank { "학교 일정" },
                        dateIso = date.toString(),
                        sourceLabel = SourceConfigs.get(metadata.sourceId)?.label ?: record.appLabel,
                    )
                }
        }.distinctBy { identity(it.record) to it.dateIso }
            .sortedWith(compareBy<SourceAgendaItem> { it.dateIso }.thenByDescending { it.record.receivedAt })
    }

    fun appliesToChild(metadata: RecordSourceMetadata, child: ChildNoticeProfile): Boolean {
        return SourceAudienceEvaluator.appliesToChild(metadata.audienceFacts, child.schoolLevel, child.grade)
    }
}
