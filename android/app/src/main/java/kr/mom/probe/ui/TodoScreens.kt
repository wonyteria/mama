package kr.mom.probe.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.sync.SourceAgendaItem
import kr.mom.probe.task.AssistantTask
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.task.TaskActionKind
import kr.mom.probe.task.TodoSelectors

/** Shared task row used by 오늘 and 할 일. Tapping expands checklist and actions. */
@Composable
fun TaskRow(
    task: AssistantTask,
    now: Long,
    expanded: Boolean,
    busy: Boolean,
    onToggle: (AssistantTask) -> Unit,
    onToggleItem: (AssistantTask, String) -> Unit,
    onExpand: (AssistantTask) -> Unit,
    onSnooze: (AssistantTask) -> Unit,
    onEdit: (AssistantTask) -> Unit,
    onExclude: (AssistantTask) -> Unit,
) {
    var showEvidence by remember(task.id, task.sourceRevisionId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().flatSurface(if (task.completed) Clay.Background else Clay.Paper)
            .clickable(role = Role.Button) { onExpand(task) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Checkbox(
                checked = task.completed,
                onCheckedChange = { onToggle(task) },
                enabled = !busy && !task.excluded,
                modifier = Modifier.minTouchTarget().testTag("task-check-${task.id}"),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    task.text,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    color = if (task.completed) Clay.Muted else Clay.Ink,
                )
                val statusLabel = when {
                    task.excluded -> "제외됨"
                    task.completed -> "완료"
                    task.suspended -> "공지 변경 확인 필요"
                    else -> "진행 중"
                }
                val metadata = buildList {
                    task.actionKind?.let { kind ->
                        add(runCatching { TaskActionKind.valueOf(kind.uppercase()).label }.getOrDefault(kind))
                    }
                    task.audienceLabel?.let(::add)
                    add(task.dueAt?.let { "기한 ${displayTime(it)}" } ?: "날짜 없음")
                    task.sourceLabel?.let(::add)
                    add(statusLabel)
                }
                Text(
                    metadata.joinToString(" · "),
                    color = if (TodoSelectors.dueLabel(task, now) == "기한 지남") Clay.Error else Clay.Muted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TodoSelectors.progressText(task)?.let {
                    Text(it, color = Clay.Green, style = MaterialTheme.typography.bodySmall)
                }
                task.remindAt?.let {
                    Text("알림 ${displayTime(it)} 예정", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (expanded) {
            task.checklist.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().toggleable(item.done, role = Role.Checkbox) { onToggleItem(task, item.id) }
                        .padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(item.done, onCheckedChange = null, enabled = !busy && !task.completed && !task.excluded)
                    Text(
                        item.text,
                        Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done || task.completed) TextDecoration.LineThrough else null,
                        color = if (item.done || task.completed) Clay.Muted else Clay.Ink,
                    )
                }
            }
            if (task.evidenceText != null) {
                TextButton(onClick = { showEvidence = true }, modifier = Modifier.padding(start = 40.dp).minTouchTarget()) { Text("근거 보기") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!task.completed && !task.excluded) {
                    TextButton(onClick = { onSnooze(task) }, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("미루기") }
                    TextButton(onClick = { onEdit(task) }, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("수정") }
                    TextButton(onClick = { onExclude(task) }, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("제외") }
                }
                if (task.completed) {
                    TextButton(onClick = { onToggle(task) }, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("완료 되돌리기") }
                }
                if (task.excluded) {
                    TextButton(onClick = { onExclude(task) }, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("제외 해제") }
                }
            }
        }
    }
    if (showEvidence) {
        TaskEvidenceDialog(task) { showEvidence = false }
    }
}

@Composable
private fun TaskEvidenceDialog(task: AssistantTask, onDismiss: () -> Unit) {
    val currentEvidence = task.evidenceText ?: return
    val originalEvidence = task.originalEvidenceText ?: currentEvidence
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("할 일 근거") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                task.sourceTitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(
                    listOfNotNull(
                        task.sourceLabel,
                        task.sourceCapturedAt?.let { displayTime(it) },
                        task.audienceLabel,
                    ).joinToString(" · "),
                    color = Clay.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                task.revisionSummary?.let {
                    StatusPill("공지 수정 반영")
                    Text(it, color = Clay.CoralDark, style = MaterialTheme.typography.bodyMedium)
                }
                if (originalEvidence != currentEvidence) {
                    Text("처음 근거", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(originalEvidence) }
                    Text("현재 근거", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(currentEvidence) }
                } else {
                    Text("원문 근거", style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(currentEvidence) }
                }
                Text(
                    "위 문구는 저장된 공지 원문이고, 할 일과 기한은 MAMA가 해석한 결과예요.",
                    color = Clay.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.minTouchTarget()) { Text("닫기") } },
    )
}

@Composable
private fun SnoozeDialog(task: AssistantTask, onPick: (Long?) -> Unit) {
    val seoul = ZoneId.of("Asia/Seoul")
    val now = System.currentTimeMillis()
    val options = remember(now) {
        listOf(
            "1시간 뒤" to now + 3_600_000L,
            "내일 아침 7시" to Instant.ofEpochMilli(now).atZone(seoul).toLocalDate().plusDays(1)
                .atTime(7, 0).atZone(seoul).toInstant().toEpochMilli(),
        )
    }
    AlertDialog(
        onDismissRequest = { onPick(null) },
        title = { Text("언제 다시 알려드릴까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("할 일은 완료될 때까지 목록에 남아요.", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
                options.forEach { (label, at) ->
                    OutlinedButton(onClick = { onPick(at) }, modifier = Modifier.fillMaxWidth().minTouchTarget()) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }, modifier = Modifier.minTouchTarget()) { Text("취소") } },
    )
}

@Composable
private fun TaskEditDialog(
    task: AssistantTask?,
    initialText: String,
    busy: Boolean,
    onSave: (String, Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable(task?.id) { mutableStateOf(initialText) }
    var dueAt by remember(task?.id) { mutableStateOf(task?.dueAt) }
    val valid = text.trim().length in 1..AssistantTaskStore.MAX_TEXT
    fun pickDue() {
        val initial = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
            timeInMillis = dueAt ?: (System.currentTimeMillis() + 86_400_000L)
        }
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                dueAt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show()
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (task == null) "할 일 직접 추가" else "할 일 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= AssistantTaskStore.MAX_TEXT) text = it },
                    modifier = Modifier.fillMaxWidth().testTag("task-edit-text"),
                    label = { Text("해야 할 일") },
                    singleLine = false,
                    maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dueAt?.let { "기한 ${displayTime(it)}" } ?: "기한 없음",
                        Modifier.weight(1f),
                        color = Clay.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = ::pickDue, modifier = Modifier.minTouchTarget()) { Text("기한 정하기") }
                    if (dueAt != null) TextButton(onClick = { dueAt = null }, modifier = Modifier.minTouchTarget()) { Text("지우기") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim(), dueAt) }, enabled = valid && !busy, modifier = Modifier.minTouchTarget()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.minTouchTarget()) { Text("취소") } },
    )
}

@Composable
fun TodayScreen(
    settings: ProbeSettings,
    tasks: List<AssistantTask>,
    records: List<ProbeRecord>,
    unreadCount: Int,
    configured: Boolean,
    notificationsAllowed: Boolean,
    sourceAgenda: List<SourceAgendaItem>,
    sourceStatusMessage: String?,
    busy: Boolean,
    now: Long = System.currentTimeMillis(),
    onSetup: () -> Unit,
    onToggle: (AssistantTask) -> Unit,
    onToggleItem: (AssistantTask, String) -> Unit,
    onSnooze: (AssistantTask, Long) -> Unit,
    onEdit: (AssistantTask, String, Long?) -> Unit,
    onExclude: (AssistantTask) -> Unit,
    onOpenTodo: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenSettings: () -> Unit,
    onRecord: (ProbeRecord) -> Unit,
    onEnableNotifications: () -> Unit,
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozeTarget by remember { mutableStateOf<AssistantTask?>(null) }
    var editTarget by remember { mutableStateOf<AssistantTask?>(null) }
    val overdue = remember(tasks, now) { TodoSelectors.overdue(tasks, now) }
    val dueSoon = remember(tasks, now) { TodoSelectors.dueSoon(tasks, now, daysAhead = 1) }
    val undated = remember(tasks) { TodoSelectors.undated(tasks) }
    val openCount = remember(tasks) { TodoSelectors.openCount(tasks) }
    val shownTasks = remember(overdue, dueSoon, undated) { (overdue + dueSoon + undated).distinctBy { it.id } }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Brand() }
            TextButton(onClick = onOpenSettings, modifier = Modifier.minTouchTarget().testTag("open-settings")) { Text("설정") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (openCount > 0) "남은 할 일\n${openCount}개" else "오늘 챙길 일을\n다 끝냈어요",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (LocalDensity.current.fontScale <= 1.25f) BellMascot(Modifier.size(92.dp, 110.dp))
        }
        if (!configured) {
            ClayCard(tint = Clay.Sage) {
                StatusPill("모모 준비 중")
                Text("마지막 준비를 도와드릴게요", style = MaterialTheme.typography.titleLarge)
                Text("한 번만 설정하면, 고른 곳의 새 소식을 이 휴대폰에서 확인할 수 있어요.", color = Clay.Muted)
                AgentButton("이어서 설정하기", Modifier.testTag("resume-setup"), onClick = onSetup)
            }
        }
        if (!notificationsAllowed) {
            Column(
                Modifier.fillMaxWidth().flatSurface().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("알림이 꺼져 있어요", style = MaterialTheme.typography.titleMedium)
                Text("할 일과 브리핑은 앱 안에서 계속 확인할 수 있어요. 소리 알림을 받으려면 알림을 켜주세요.", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onEnableNotifications, modifier = Modifier.align(Alignment.End).minTouchTarget()) { Text("알림 켜기") }
            }
        }
        sourceStatusMessage?.let {
            Column(
                Modifier.fillMaxWidth().flatSurface().clickable(role = Role.Button, onClick = onOpenSettings).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(it, color = Clay.Error, style = MaterialTheme.typography.bodyMedium)
                Text("출처 상태와 복구 방법은 설정에서 확인할 수 있어요.", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (configured && shownTasks.isEmpty()) {
            EmptyCard("남은 할 일이 없어요", "새 소식에서 확인할 일이 생기면 여기 모여요. 직접 추가할 수도 있어요.")
        }
        if (overdue.isNotEmpty()) {
            Eyebrow("기한 지남")
            overdue.take(5).forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (dueSoon.isNotEmpty()) {
            Eyebrow("오늘·내일 챙길 일")
            dueSoon.take(5).forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (undated.isNotEmpty()) {
            Eyebrow("날짜 미정")
            undated.take(5).forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (openCount > shownTasks.size || openCount > 0) {
            TextButton(onClick = onOpenTodo, modifier = Modifier.align(Alignment.CenterHorizontally).minTouchTarget().testTag("open-todo-all")) {
                Text("할 일 모두 보기  ›")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoCard(Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenNews), Clay.Sage) {
                Text("새 소식", color = Clay.Green, style = MaterialTheme.typography.bodySmall)
                Text("${unreadCount}개", style = MaterialTheme.typography.titleLarge)
                Text(if (unreadCount > 0) "읽지 않은 소식" else "모두 확인했어요", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
            }
            BentoCard(Modifier.weight(1f), Clay.Peach) {
                Text("일정", color = Clay.CoralDark, style = MaterialTheme.typography.bodySmall)
                Text("${sourceAgenda.size}개", style = MaterialTheme.typography.titleLarge)
                Text(
                    sourceAgenda.firstOrNull()?.let { "${briefDate(it.dateIso)} ${it.title}" } ?: "저장된 학교 일정 없음",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Clay.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val childProfile = remember(settings.schoolGrade, settings.schoolLevel, settings.schoolName) {
            NoticeDecisionEngine.childProfile(settings)
        }
        val institution = remember(settings.schoolName) { NoticeGrouping.institution(settings) }
        val reviewRecords = remember(records, tasks, childProfile, institution) {
            todayReviewRecords(records, tasks, childProfile, institution)
        }
        if (reviewRecords.isNotEmpty()) {
            Eyebrow("확인할 소식")
            reviewRecords.forEach { RecordCard(it, onClick = { onRecord(it) }) }
        }
    }
    snoozeTarget?.let { target ->
        SnoozeDialog(target) { at ->
            snoozeTarget = null
            if (at != null) onSnooze(target, at)
        }
    }
    editTarget?.let { target ->
        TaskEditDialog(target, target.text, busy, { text, due -> editTarget = null; onEdit(target, text, due) }, { editTarget = null })
    }
}

internal fun todayReviewRecords(
    records: List<ProbeRecord>,
    tasks: List<AssistantTask>,
    childProfile: kr.mom.probe.data.ChildNoticeProfile,
    institution: String,
): List<ProbeRecord> {
    val linkedKeySets = tasks.map { it.noticeGroupKeys + listOfNotNull(it.sourceNotificationId) }
        .filter { it.isNotEmpty() }
    return NoticeGrouping.representatives(records, institution)
        .filter { record ->
            val recordKeys = NoticeGrouping.keys(record, institution)
            linkedKeySets.none { NoticeGrouping.matches(recordKeys, it) }
        }
        .map { it to NoticeDecisionEngine.decide(it, childProfile) }
        .filter { it.second.isRequiredForChild() || it.second.isOptionalForChild() }
        .sortedByDescending { it.first.receivedAt }
        .take(3)
        .map { it.first }
}

@Composable
fun TodoScreen(
    tasks: List<AssistantTask>,
    busy: Boolean,
    now: Long = System.currentTimeMillis(),
    onToggle: (AssistantTask) -> Unit,
    onToggleItem: (AssistantTask, String) -> Unit,
    onSnooze: (AssistantTask, Long) -> Unit,
    onEdit: (AssistantTask, String, Long?) -> Unit,
    onExclude: (AssistantTask) -> Unit,
    onAddTask: (String, Long?) -> Unit,
) {
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var snoozeTarget by remember { mutableStateOf<AssistantTask?>(null) }
    var editTarget by remember { mutableStateOf<AssistantTask?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    val overdue = remember(tasks, now) { TodoSelectors.overdue(tasks, now) }
    val dueSoon = remember(tasks, now) { TodoSelectors.dueSoon(tasks, now, daysAhead = 1) }
    val later = remember(tasks, now) { TodoSelectors.later(tasks, now, daysAhead = 1) }
    val undated = remember(tasks) { TodoSelectors.undated(tasks) }
    val done = remember(tasks) { TodoSelectors.completed(tasks) }
    val excluded = remember(tasks) { TodoSelectors.excluded(tasks) }
    val openCount = remember(tasks) { TodoSelectors.openCount(tasks) }
    Page {
        Eyebrow("끝낼 때까지 함께 챙겨요")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("할 일 ${openCount}개", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
        }
        ClayButton("할 일 직접 추가", Modifier.testTag("add-task"), primary = false, onClick = { adding = true })
        if (openCount == 0 && done.isEmpty() && excluded.isEmpty()) {
            EmptyCard("아직 할 일이 없어요", "공지에서 확인된 일이나 직접 적은 일이 여기 모여요.")
        }
        if (overdue.isNotEmpty()) {
            Eyebrow("기한 지남")
            overdue.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (dueSoon.isNotEmpty()) {
            Eyebrow("오늘·내일")
            dueSoon.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (later.isNotEmpty()) {
            Eyebrow("이후")
            later.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (undated.isNotEmpty()) {
            Eyebrow("날짜 미정")
            undated.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (done.isNotEmpty()) {
            TextButton(onClick = { showDone = !showDone }, modifier = Modifier.align(Alignment.CenterHorizontally).minTouchTarget()) {
                Text(if (showDone) "완료한 일 접기" else "완료한 일 ${done.size}개 보기")
            }
            if (showDone) done.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
        if (excluded.isNotEmpty()) {
            TextButton(onClick = { showExcluded = !showExcluded }, modifier = Modifier.align(Alignment.CenterHorizontally).minTouchTarget()) {
                Text(if (showExcluded) "제외한 일 접기" else "제외한 일 ${excluded.size}개 보기")
            }
            if (showExcluded) excluded.forEach { task ->
                TaskRow(task, now, expandedId == task.id, busy, onToggle, onToggleItem,
                    { expandedId = if (expandedId == it.id) null else it.id },
                    { snoozeTarget = it }, { editTarget = it }, onExclude)
            }
        }
    }
    snoozeTarget?.let { target ->
        SnoozeDialog(target) { at ->
            snoozeTarget = null
            if (at != null) onSnooze(target, at)
        }
    }
    editTarget?.let { target ->
        TaskEditDialog(target, target.text, busy, { text, due -> editTarget = null; onEdit(target, text, due) }, { editTarget = null })
    }
    if (adding) {
        TaskEditDialog(null, "", busy, { text, due -> adding = false; onAddTask(text, due) }, { adding = false })
    }
}
