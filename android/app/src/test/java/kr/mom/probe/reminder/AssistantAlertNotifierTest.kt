package kr.mom.probe.reminder

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeRules
import kr.mom.probe.data.SchoolLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AssistantAlertNotifierTest {
    private val now = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()

    @Test fun allowsOnlyFutureRequiredApplicablePreciseDeadlines() {
        val required = decision("준비물 안내", "준비물: 물통. 오늘 오전 10시까지", ChildNoticeProfile())
        val optional = decision("토요미래산책 신청 안내", "대상 초등 5~6학년. 신청 2026-09-28 월 17:00 ~ 2026-10-02 금 09:00. 선착순 마감.", ChildNoticeProfile(5, SchoolLevel.ELEMENTARY))
        val ineligible = decision("초등 5학년 준비물", "초등 5학년 준비물: 물통. 오늘 오전 10시까지", ChildNoticeProfile(2, SchoolLevel.ELEMENTARY))
        val past = decision("준비물 안내", "준비물: 물통. 오늘 오전 8시까지", ChildNoticeProfile())

        val nextBriefing = Instant.parse("2026-09-15T02:00:00Z").toEpochMilli()

        assertTrue(AssistantAlertNotifier.shouldNotify(required, now, nextBriefing))
        assertFalse(AssistantAlertNotifier.shouldNotify(optional, now, nextBriefing))
        assertFalse(AssistantAlertNotifier.shouldNotify(ineligible, now, nextBriefing))
        assertFalse(AssistantAlertNotifier.shouldNotify(past, Instant.parse("2026-09-15T00:30:00Z").toEpochMilli(), nextBriefing))
        assertFalse(AssistantAlertNotifier.shouldNotify(required, now, Instant.parse("2026-09-15T00:30:00Z").toEpochMilli()))
    }

    @Test fun alertFingerprintIgnoresWhitespaceButChangesWhenDeadlineChanges() {
        val original = decision("준비물 안내", "준비물: 물통. 오늘 오전 10시까지", ChildNoticeProfile())
        val whitespaceOnly = decision("준비물 안내", "준비물:   물통. 오늘 오전 10시까지", ChildNoticeProfile())
        val changedDeadline = decision("준비물 안내", "준비물: 물통. 오늘 오전 11시까지", ChildNoticeProfile())

        assertEquals(
            AssistantAlertNotifier.alertFingerprintForTest(original),
            AssistantAlertNotifier.alertFingerprintForTest(whitespaceOnly),
        )
        assertNotEquals(
            AssistantAlertNotifier.alertFingerprintForTest(original),
            AssistantAlertNotifier.alertFingerprintForTest(changedDeadline),
        )
        assertFalse(AssistantAlertNotifier.alertFingerprintForTest(original)!!.contains("물통"))
    }

    @Test fun cancelingPostedUrgentNotificationDoesNotEraseDuplicateHistory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AssistantAlertNotifier.reset(context)
        val record = record("준비물 안내", "준비물: 물통. 오늘 오전 10시까지")
        val decision = NoticeDecisionEngine.decide(record, ChildNoticeProfile())
        val sourceId = ProbeRules.notificationIdentity(record.packageName, record.notificationKey)
        val fingerprint = AssistantAlertNotifier.alertFingerprintForTest(decision)!!

        AssistantAlertNotifier.recordAlertedForTest(context, sourceId, fingerprint)
        AssistantAlertNotifier.cancel(context, record.copy(id = "revision-2", rawHash = "same-action-new-revision"))

        assertEquals(fingerprint, AssistantAlertNotifier.storedAlertFingerprintForTest(context, sourceId))
    }

    @Test fun `posted flag tracks the unified alert lifecycle`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AssistantAlertNotifier.reset(context)
        val record = record("준비물 안내", "준비물: 물통. 오늘 오전 10시까지")
        val sourceId = ProbeRules.notificationIdentity(record.packageName, record.notificationKey)

        assertFalse(AssistantAlertNotifier.isAlertPosted(context, sourceId))
        AssistantAlertNotifier.markAlertPostedForTest(context, sourceId, record.id, 4242)
        assertTrue(AssistantAlertNotifier.isAlertPosted(context, sourceId))
        assertEquals(record.id, AssistantAlertNotifier.linkedRecordId(context, sourceId))

        AssistantAlertNotifier.cancel(context, record)
        assertFalse(AssistantAlertNotifier.isAlertPosted(context, sourceId))
        assertEquals(null, AssistantAlertNotifier.linkedRecordId(context, sourceId))
    }

    @Test fun `dismissed unified alert makes future originals stay visible`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        AssistantAlertNotifier.reset(context)
        val record = record("준비물 안내", "준비물: 물통. 오늘 오전 10시까지")
        val sourceId = ProbeRules.notificationIdentity(record.packageName, record.notificationKey)
        val alertId = ProbeRules.recordIdentity(record).hashCode()

        AssistantAlertNotifier.markAlertPostedForTest(context, sourceId, record.id, alertId)
        assertTrue(AssistantAlertNotifier.isAlertPosted(context, sourceId))

        AssistantAlertNotifier.markAlertGone(context, alertId)
        assertFalse(AssistantAlertNotifier.isAlertPosted(context, sourceId))
        assertEquals(null, AssistantAlertNotifier.linkedRecordId(context, sourceId))
        // An unknown notification id must not disturb other posted alerts.
        AssistantAlertNotifier.markAlertPostedForTest(context, sourceId, record.id, alertId)
        AssistantAlertNotifier.markAlertGone(context, alertId + 1)
        assertTrue(AssistantAlertNotifier.isAlertPosted(context, sourceId))
    }

    private fun decision(title: String, text: String, child: ChildNoticeProfile) = NoticeDecisionEngine.decide(
        record(title, text),
        child,
    )

    private fun record(title: String, text: String) = ProbeRecord(
            id = "id-${title.hashCode()}-${text.hashCode()}",
            packageName = "school.app",
            appLabel = "학교",
            postedAt = now,
            receivedAt = now,
            title = title,
            text = text,
            bigText = "",
            textLines = emptyList(),
            subText = null,
            summaryText = null,
            category = null,
            channelId = null,
            notificationId = 1,
            notificationKey = "key-${title.hashCode()}",
            isOngoing = false,
            isGroupSummary = false,
            rawHash = "hash",
    )
}
