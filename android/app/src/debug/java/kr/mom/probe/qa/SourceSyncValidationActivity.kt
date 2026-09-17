package kr.mom.probe.qa

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.WorkManager
import java.time.LocalDate
import java.time.ZoneId
import kr.mom.probe.BuildConfig
import kr.mom.probe.agent.LocalAgentContext
import kr.mom.probe.agent.LocalAgentEngine
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeContentState
import kr.mom.probe.data.NoticeDateRole
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.FetchedNotice
import kr.mom.probe.sync.SourceAudienceFact
import kr.mom.probe.sync.SourceCoverageWindow
import kr.mom.probe.sync.SourceDateFact
import kr.mom.probe.sync.SourceEvidence
import kr.mom.probe.sync.SourceFetchResult
import kr.mom.probe.sync.SourceFetcher
import kr.mom.probe.sync.SourceFetcherRegistry
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceOrigin
import kr.mom.probe.sync.SourceRecordSelectors
import kr.mom.probe.sync.SourceRunTrigger
import kr.mom.probe.sync.SourceScope
import kr.mom.probe.sync.SourceScopeFactory
import kr.mom.probe.sync.SourceSyncScheduler
import kr.mom.probe.sync.SourceSyncStateStore
import kr.mom.probe.sync.SourceSyncStatus
import kr.mom.probe.reminder.BriefingReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceSyncValidationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        output = TextView(this).apply { textSize = 14f }
        val run = Button(this).apply {
            text = "Initialize synthetic grade 2 and enqueue production worker"
            setOnClickListener { runValidation() }
        }
        val repeat = Button(this).apply {
            text = "Repeat production worker"
            setOnClickListener {
                SourceSyncScheduler.enqueue(this@SourceSyncValidationActivity, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
                render("repeat-enqueued")
            }
        }
        val reload = Button(this).apply {
            text = "Reload sanitized source counts"
            setOnClickListener { render("reload") }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(run, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(repeat, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(reload, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(output, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        render("ready")
    }

    private fun runValidation() {
        scope.launch {
            output.text = "initializing..."
            withContext(Dispatchers.IO) {
                val repository = ProbeRepository.get(this@SourceSyncValidationActivity)
                repository.isReady.first { it }
                repository.acceptConsent()
                repository.saveSourceSelection(setOf("qa.validation.source"))
                repository.saveChild("QA", "성남정자초등학교", 2, SchoolLevel.ELEMENTARY)
                repository.deferSetup()
                SourceFetcherRegistry.register(SourceIds.SCHOOL_WEBSITE, QaSourceFetcher())
                SourceSyncScheduler.enqueue(this@SourceSyncValidationActivity, SourceIds.SCHOOL_WEBSITE, SourceRunTrigger.MANUAL)
            }
            render("initialized-and-enqueued")
        }
    }

    private fun render(label: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val repository = ProbeRepository.get(this@SourceSyncValidationActivity)
                repository.isReady.first { it }
                val state = SourceSyncStateStore.get(this@SourceSyncValidationActivity).snapshot(SourceIds.SCHOOL_WEBSITE)
                val scopes = SourceScopeFactory.activeScopes(this@SourceSyncValidationActivity, SourceRunTrigger.MANUAL)
                val records = repository.records.value
                val sourceRecords = records.filter { it.sourceMetadata?.sourceId == SourceIds.SCHOOL_WEBSITE }
                val ids = sourceRecords.map { it.id.take(12) }.sorted()
                val child = NoticeDecisionEngine.childProfile(repository.settings.value)
                val agenda = SourceRecordSelectors.agenda(records, child, scopes)
                val chat = LocalAgentEngine().answer(
                    "내일 학교 일정 뭐야?",
                    LocalAgentContext("QA", records, emptyList(), child, scopes),
                )
                val briefing = BriefingReminders.briefingAgenda(this@SourceSyncValidationActivity, records, repository.settings.value, slot = 1)
                val work = WorkManager.getInstance(this@SourceSyncValidationActivity)
                    .getWorkInfosForUniqueWork("source-sync-now-${SourceIds.SCHOOL_WEBSITE}").get()
                    .joinToString { it.state.name }
                buildString {
                    appendLine("label=$label")
                    appendLine("debug=${BuildConfig.DEBUG}")
                    appendLine("sourceId=${SourceIds.SCHOOL_WEBSITE}")
                    appendLine("activeScopes=${scopes.size}")
                    appendLine("snapshotStatus=${state?.status ?: SourceSyncStatus.NEVER}")
                    appendLine("lastSuccessAt=${state?.lastSuccessAt ?: 0}")
                    appendLine("seen=${state?.seenCount ?: 0} stored=${state?.storedCount ?: 0}")
                    appendLine("sourceRecords=${sourceRecords.size}")
                    appendLine("agendaCount=${agenda.size}")
                    appendLine("chatHasQaAgenda=${chat.message.contains("QA 학교 일정")}")
                    appendLine("briefingAgendaCount=${briefing.size}")
                    appendLine("recordIds=${ids.joinToString(",")}")
                    appendLine("work=$work")
                }
            }
            output.text = text
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

private class QaSourceFetcher : SourceFetcher {
    override suspend fun fetch(scope: SourceScope, checkpoint: kr.mom.probe.sync.SourceCheckpoint?): SourceFetchResult {
        val now = System.currentTimeMillis()
        val tomorrow = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(1).toString()
        val item = FetchedNotice(
            sourceId = SourceIds.SCHOOL_WEBSITE,
            itemId = "qa-grade2-informational-event",
            revisionHash = "qa-revision-v1",
            firstSeenAt = now,
            publishedAt = now,
            title = "QA 학교 일정",
            body = "QA 검증용 초등 2학년 학교 일정입니다.",
            origin = SourceOrigin("https://snjj-e.goesn.kr/snjj-e/qa", "snjj-e.goesn.kr", "qa", "qa-grade2-informational-event"),
            contentState = NoticeContentState.VERIFIED,
            obligation = NoticeObligation.INFORMATIONAL,
            audienceFacts = listOf(SourceAudienceFact(
                applicability = NoticeApplicability.APPLIES,
                schoolLevel = SchoolLevel.ELEMENTARY,
                gradeStart = 2,
                gradeEnd = 2,
                evidence = SourceEvidence("대상", "초등 2학년"),
            )),
            dateFacts = listOf(SourceDateFact(NoticeDateRole.EVENT, "내일", tomorrow, evidence = SourceEvidence("일정", tomorrow))),
            evidence = listOf(SourceEvidence("qa", "debug-only synthetic fetcher")),
        )
        return SourceFetchResult(
            sourceId = SourceIds.SCHOOL_WEBSITE,
            status = SourceSyncStatus.FETCHED,
            fetchedAt = now,
            items = listOf(item),
            coverage = SourceCoverageWindow(pageStart = 1, pageEnd = 1, complete = true),
        )
    }
}
