package kr.mom.probe.ui

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.TimeZone
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import kr.mom.probe.BuildConfig
import kr.mom.probe.data.ChildNoticeProfile
import kr.mom.probe.data.NoticeApplicability
import kr.mom.probe.data.NoticeDecision
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.NoticeObligation
import kr.mom.probe.data.ProbeRecord
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.NotificationCandidateParser
import kr.mom.probe.sync.SourceAgendaItem

fun displayTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Seoul"))
    .format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))

@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp), content = content)
}

@Composable
fun BackHeading(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "뒤로" }) { Text("‹", fontSize = 30.sp) }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun Brand() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Eyebrow("엄마의 하루를 함께 챙기는 모모")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("나는 엄마다", style = MaterialTheme.typography.titleLarge)
            LeafMark()
        }
    }
}

@Composable
fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun WelcomeScreen(busy: Boolean, onStart: () -> Unit, onPolicy: () -> Unit) {
    var agreed by rememberSaveable { mutableStateOf(false) }
    Page {
        Brand()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("엄마의 하루에,\n작은 여유를.", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            if (LocalDensity.current.fontScale <= 1.25f) BellMascot(Modifier.size(106.dp, 130.dp))
        }
        Text("학교·학원 앱과 사이트,\n한곳에서 챙길 준비를 해요.", style = MaterialTheme.typography.titleLarge)
        ClayCard(tint = Clay.Sage) {
            StatusPill("가볍게 시작해요")
            Text("자녀 정보 → 필요한 앱·사이트 연결", style = MaterialTheme.typography.titleMedium)
            Text("아이 이름만으로 시작하고, 필요한 앱·사이트를 골라요.\n계정 비밀번호를 우리 앱에 저장하지 않아요.", color = Clay.Muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("선택한 앱의 새 알림을 모모가 기기 안에서 정리해요. 날짜와 행동이 분명한 일은 자동으로 부탁과 알림을 만들어요.", style = MaterialTheme.typography.bodyMedium, color = Clay.Muted)
            Text("선택한 앱의 알림과 자녀 정보는 이 기기에 암호화해 보관해요. 알림 원문은 14일 뒤 삭제하며 재설치하면 복구할 수 없어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
            TextButton(onClick = onPolicy) { Text("수집·보관·삭제 설명 보기") }
            ConsentRow(agreed, { agreed = it }, "설명을 확인했고, 이 기기에서 알림을 모아 챙길 후보를 찾는 데 동의해요. (필수)")
        }
        ClayButton(if (busy) "준비하고 있어요…" else "이 기기에서 시작", Modifier.testTag("start"), agreed && !busy, onClick = onStart)
    }
}

@Composable
fun SourcesScreen(apps: List<SourceApp>, initial: Set<String>, busy: Boolean, allowEmpty: Boolean = false, onSave: (Set<String>) -> Unit,
                  onBack: () -> Unit, onSkip: () -> Unit, onRefresh: () -> Unit) {
    var selected by remember(apps, initial) { mutableStateOf(initial.intersect(apps.map { it.packageName }.toSet())) }
    var showCatalog by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val initialInstalled = initial.intersect(apps.map { it.packageName }.toSet())
    fun requestBack() { if (selected != initialInstalled) discard = true else onBack() }
    BackHandler(selected != initialInstalled) { discard = true }
    Page {
        BackHeading("챙길 앱 고르기", ::requestBack)
        Eyebrow("1 / 3  ·  챙길 앱")
        Text("어떤 앱을\n챙겨드릴까요?", style = MaterialTheme.typography.headlineLarge)
        Text("휴대폰에서 찾은 테스트 대상 앱이에요.\n하나만 골라도 시작할 수 있어요.", color = Clay.Muted)
        if (apps.isEmpty()) {
            ClayCard(tint = Clay.Sage) {
                Text("아직 연결할 앱을 찾지 못했어요", style = MaterialTheme.typography.titleMedium)
                Text("학교·학원 앱이 이 휴대폰에 설치되어 있는지 확인해주세요. 설치되어도 목록에 없는 앱은 이번 테스트 대상이 아니에요.", color = Clay.Muted)
                ClayButton("설치한 앱 다시 확인", primary = false, onClick = onRefresh)
            }
        } else {
            apps.forEachIndexed { index, app ->
                val checked = app.packageName in selected
                val allowed = checked || selected.size < 10
                Row(Modifier.fillMaxWidth().claySurface(if (checked) Clay.Sage else Clay.Background)
                    .toggleable(checked, enabled = allowed, role = Role.Checkbox) {
                        selected = if (checked) selected - app.packageName else selected + app.packageName
                    }.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).claySurface(if (index % 2 == 0) Clay.Peach else Clay.Lavender, 16.dp), contentAlignment = Alignment.Center) {
                        Text(app.mark, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Clay.Ink)
                    }
                    Column(Modifier.weight(1f)) { Text(app.name, fontWeight = FontWeight.Bold); Text(app.category, color = Clay.Muted, style = MaterialTheme.typography.bodySmall) }
                    Checkbox(checked, onCheckedChange = null, enabled = allowed)
                }
            }
            Text("${selected.size}개 선택 · 알림 수집 가능 여부는 테스트 중이에요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        ClayButton(if (allowEmpty) "선택 저장" else "이 앱 챙기기", Modifier.testTag("save-sources"), (selected.isNotEmpty() || allowEmpty) && !busy, onClick = { onSave(selected) })
        if (allowEmpty) Text("선택을 해제하면 해당 앱의 새 알림 수집만 멈춰요. 모아둔 알림은 14일 보관 규칙에 따라 남아 있어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("나중에 설정할게요") }
        TextButton(onClick = { showCatalog = !showCatalog }) { Text(if (showCatalog) "테스트 대상 접기" else "어떤 앱이 테스트 대상인가요?") }
        if (showCatalog) Text(SourceCatalog.candidates.joinToString(" · ") { it.name } + "\n앱 계정 연결이나 공지 원문 전체 접근을 보장하지 않아요.", color = Clay.Muted, style = MaterialTheme.typography.bodyMedium)
    }
    if (discard) DiscardDialog({ discard = false }, { discard = false; onBack() })
}

@Composable
fun ChildScreen(initial: String, busy: Boolean, onSave: (String) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    var discard by remember { mutableStateOf(false) }
    fun requestBack() { if (name != initial) discard = true else onBack() }
    BackHandler(name != initial) { discard = true }
    val valid = name.trim().isNotEmpty() && name.trim().length <= 20 && name.none { it.isISOControl() }
    Page {
        BackHeading("아이 이름", ::requestBack)
        Eyebrow("2 / 3  ·  아이 이름")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("어떻게\n불러드릴까요?", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge)
            if (LocalDensity.current.fontScale <= 1.25f) BellMascot(Modifier.size(100.dp, 118.dp))
        }
        Text("이름이나 별칭 하나만 알려주세요.\n학교·학원 정보는 지금 입력하지 않아도 돼요.", color = Clay.Muted)
        OutlinedTextField(name, { if (it.length <= 40) name = it }, modifier = Modifier.fillMaxWidth().testTag("child-name"),
            label = { Text("아이 이름 또는 별칭") }, placeholder = { Text("예: 서윤, 우리 아이") },
            singleLine = true, shape = RoundedCornerShape(22.dp), isError = name.isNotEmpty() && !valid,
            supportingText = { Text(if (name.isNotEmpty() && !valid) "이름 또는 별칭을 1~20자로 입력해주세요." else "이 휴대폰 안에만 안전하게 저장해요.") })
        ClayButton(if (busy) "저장하고 있어요…" else "다음", Modifier.testTag("save-child"), valid && !busy, onClick = { onSave(name.trim()) })
    }
    if (discard) DiscardDialog({ discard = false }, { discard = false; onBack() })
}

@Composable
private fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text("입력한 내용을 남겨둘까요?") },
        text = { Text("아직 저장하지 않았어요. 돌아가면 지금 바꾼 내용은 저장되지 않아요.") },
        confirmButton = { TextButton(onClick = onKeep) { Text("계속 입력") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("저장하지 않고 돌아가기") } })
}

@Composable
fun AccessScreen(labels: List<String>, busy: Boolean, onOpen: () -> Unit, onSkip: () -> Unit, onBack: () -> Unit) {
    var help by rememberSaveable { mutableStateOf(false) }
    Page {
        BackHeading("마지막 설정", onBack)
        Eyebrow("3 / 3  ·  알림 읽기 허용")
        Text("이제 알림을\n읽도록 허용해요.", style = MaterialTheme.typography.headlineLarge)
        ClayCard(tint = Clay.Sage) {
            StatusPill("엄마가 고른 앱만")
            Text(labels.joinToString(" · "), style = MaterialTheme.typography.titleLarge)
            Text("이 앱에 새로 도착한 알림 내용을 확인할게요. 다른 앱의 내용은 저장하지 않아요.", color = Clay.Muted)
        }
        Text("Android는 넓은 알림 접근을 허용하는 화면을 보여줘요. 나는 엄마다는 위에서 고른 앱만 처리해요.", style = MaterialTheme.typography.bodyMedium, color = Clay.Muted)
        ClayButton("설정 열기", Modifier.testTag("open-access"), !busy, onClick = onOpen)
        Text("돌아오시면 설정됐는지 자동으로 확인할게요.", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        TextButton(onClick = { help = !help }) { Text("어디를 누르나요?  ${if (help) "−" else "+"}") }
        if (help) ClayCard {
            Text("1. 설정에서 ‘나는 엄마다’를 찾아요.\n2. 알림 읽기를 허용해요.\n3. 뒤로가기로 앱에 돌아와요.", style = MaterialTheme.typography.bodyLarge)
            Text("휴대폰에 따라 설정 이름과 위치가 달라요. ‘제한된 설정’이 보이면 설치 경로에 따른 추가 안내가 필요할 수 있어요. 어려우면 나중에 이어서 해도 괜찮아요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("나중에 설정할게요") }
    }
}

@Composable
fun HomeScreen(settings: ProbeSettings, records: List<ProbeRecord>, access: Boolean, connected: Boolean,
               onSetup: () -> Unit, onInbox: () -> Unit, onRecord: (ProbeRecord) -> Unit,
               connectedSiteCount: Int = 0,
               sourceAgenda: List<SourceAgendaItem> = emptyList(),
               sourceStatusMessage: String? = null,
               schoolEventsLimited: Boolean = false,
               pendingTaskCount: Int = 0, briefingReady: Boolean = true,
               rememberedGroupKeys: Set<Set<String>> = emptySet(),
               onAgenda: () -> Unit = onInbox,
               onEnableBriefings: () -> Unit = {}, onAssistant: (() -> Unit)? = null) {
    val hasApps = settings.selectedPackages.isNotEmpty()
    val configured = settings.childName.isNotBlank() && (connectedSiteCount > 0 || (hasApps && access))
    val active = connectedSiteCount > 0 || (configured && hasApps && settings.collectionEnabled)
    val childProfile = remember(settings.schoolGrade, settings.schoolLevel, settings.schoolName) {
        NoticeDecisionEngine.childProfile(settings)
    }
    val now = remember(records, pendingTaskCount) { System.currentTimeMillis() }
    val institution = remember(settings.schoolName) { NoticeGrouping.institution(settings) }
    val noticeDecisions = remember(records, childProfile, rememberedGroupKeys, institution) {
        val groupIds = NoticeGrouping.groupIds(records, institution)
        records.distinctBy { groupIds.getValue(it.id) }
            .map { it to NoticeDecisionEngine.decide(it, childProfile) }
    }
    fun linkedToTask(record: ProbeRecord): Boolean {
        if (rememberedGroupKeys.isEmpty()) return false
        val recordKeys = NoticeGrouping.keys(record, institution)
        return rememberedGroupKeys.any { NoticeGrouping.matches(recordKeys, it) }
    }
    val actionRecords = noticeDecisions
        .filter { (record, decision) ->
            !linkedToTask(record) && NoticeDecisionEngine.isBriefingAction(decision, now)
        }
        .sortedWith(compareBy<Pair<ProbeRecord, NoticeDecision>> { it.second.action?.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.first.receivedAt })
        .map { it.first }
    val optionalRecords = noticeDecisions
        .filter { (record, decision) ->
            !linkedToTask(record) && decision.isOptionalForChild()
        }
        .sortedByDescending { it.first.receivedAt }
    val topAction = actionRecords.firstOrNull()
    val actionCount = actionRecords.size + pendingTaskCount
    Page {
        Brand()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (configured) "오늘\n챙길 일" else "함께 챙길\n준비를 해요", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            if (LocalDensity.current.fontScale <= 1.25f) BellMascot(Modifier.size(98.dp, 118.dp))
        }
        AgentCard(Modifier.fillMaxWidth()) {
            StatusPill(when {
                !configured -> "모모 준비 중"
                !active -> "잠시 멈춤"
                actionCount > 0 -> "곧 챙길 일 ${actionCount}개"
                !briefingReady -> "브리핑 설정 필요"
                else -> "모모가 확인 중"
            })
            Text(when {
                !configured -> "마지막 준비를 도와드릴게요"
                !active -> "알림 모으기를 멈췄어요"
                topAction != null -> topAction.title.ifBlank { "확인할 새 소식이 있어요" }
                pendingTaskCount > 0 -> "제가 기억하고 있는 부탁이 있어요"
                !briefingReady -> "아침·저녁에 한 번에 정리해드릴까요?"
                else -> "지금은 급하게 챙길 일이 없어요"
            }, style = MaterialTheme.typography.titleLarge)
            Text(when {
                !configured -> "한 번만 설정하면, 고른 앱의 새 알림을\n이 휴대폰에서 확인할 수 있어요."
                !active -> "저장한 소식은 그대로 있어요.\n원할 때 다시 시작할 수 있어요."
                topAction != null -> {
                    val action = NoticeDecisionEngine.decide(topAction, childProfile).action
                    val items = NoticeDecisionEngine.extractItems("${topAction.title} ${topAction.bigText} ${topAction.text} ${topAction.textLines.joinToString(" ")}")
                        .takeIf { it.isNotEmpty() }?.joinToString(", ")
                    listOfNotNull(items, action?.whenText, topAction.appLabel.takeIf { it.isNotBlank() })
                        .joinToString(" · ")
                        .ifBlank { "원문에서 한 번 확인해 주세요." }
                }
                pendingTaskCount > 0 -> "완료할 때까지 부탁 목록에 안전하게 보관하고 있어요."
                !briefingReady -> "새 소식은 묶어서 알려드리고,\n오늘 안에 놓칠 일만 바로 알려드려요."
                else -> "새 소식이 오면 필요한 행동만 골라서 알려드릴게요."
            }, color = Clay.Muted)
            when {
                !active -> AgentButton(if (configured) "다시 시작하기" else "이어서 설정하기", Modifier.testTag("resume-setup"), onClick = onSetup)
                topAction != null -> AgentButton("내용 보기", onClick = { onRecord(topAction) })
                pendingTaskCount > 0 && onAssistant != null -> AgentButton("부탁 확인하기", onClick = onAssistant)
                !briefingReady -> AgentButton("모모 브리핑 켜기", onClick = onEnableBriefings)
                onAssistant != null -> AgentButton("모모에게 부탁하기", onClick = onAssistant)
            }
            if (onAssistant != null && (topAction != null || pendingTaskCount > 0 || !briefingReady)) {
                TextButton(onClick = onAssistant, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("부탁 목록 보기") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoCard(Modifier.weight(1f), Clay.Sage) {
                Text("일정", color = Clay.Green, style = MaterialTheme.typography.bodySmall)
                Text("${sourceAgenda.size}개", style = MaterialTheme.typography.titleLarge)
                Text(sourceAgenda.firstOrNull()?.let { "${briefDate(it.dateIso)} ${it.title}" }
                    ?: if (schoolEventsLimited) "저장된 일정 없음 · 일부 조회"
                    else "저장된 학교 일정 없음",
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
                if (sourceAgenda.isNotEmpty()) TextButton(onClick = onAgenda) { Text("일정 보기") }
                sourceStatusMessage?.let { Text(it, color = Clay.Error, style = MaterialTheme.typography.bodySmall) }
            }
            BentoCard(Modifier.weight(1f), Clay.Peach) {
                Text("챙길 일", color = Clay.CoralDark, style = MaterialTheme.typography.bodySmall)
                Text("${actionCount}개", style = MaterialTheme.typography.titleLarge)
                Text(if (actionCount > 0) "후보 ${actionRecords.size} · 부탁 $pendingTaskCount" else "새 소식을 기다려요", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (optionalRecords.isNotEmpty()) {
            val (record, decision) = optionalRecords.first()
            Column(Modifier.fillMaxWidth().flatSurface().clickable(role = Role.Button) { onRecord(record) }.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("관심 있을 만한 소식 ${optionalRecords.size}개", color = Clay.Green, style = MaterialTheme.typography.bodySmall)
                Text(record.title.ifBlank { decision.action?.label ?: "선택 활동" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(decision.action?.whenText?.let { "접수 관련 원문 표현: $it" } ?: decision.applicabilityReason,
                    color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("연결한 곳 ${settings.selectedPackages.size + connectedSiteCount}개", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        if (records.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("새로 확인한 소식", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onInbox) { Text("전체  ›", style = MaterialTheme.typography.bodySmall) }
            }
            records.take(3).forEach { RecordCard(it, onClick = { onRecord(it) }) }
        }
    }
}

private fun briefDate(value: String): String = when {
    value.length == 8 -> "${value.substring(4, 6)}월 ${value.substring(6, 8)}일"
    value.length == 10 && value[4] == '-' -> "${value.substring(5, 7)}월 ${value.substring(8, 10)}일"
    else -> value
}

@Composable
fun EmptyCard(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFFD6DED3), RoundedCornerShape(28.dp)).padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NavGlyph("inbox", false, Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = Clay.Muted, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Clay.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun RecordCard(record: ProbeRecord, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().flatSurface().clickable(role = Role.Button, onClick = onClick).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row { Text(record.appLabel, Modifier.weight(1f), color = Clay.Green, style = MaterialTheme.typography.bodySmall); Text(displayTime(record.receivedAt), color = Clay.Muted, style = MaterialTheme.typography.bodySmall) }
        Text(record.title.ifBlank { "제목 없는 알림" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val body = record.bigText.ifBlank { record.text }.ifBlank { record.textLines.joinToString(" ") }
        Text(body.ifBlank { "알림에 내용이 없어요. 원래 앱에서 확인해주세요." }, color = Clay.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun InboxScreen(records: List<ProbeRecord>, onRecord: (ProbeRecord) -> Unit, onExport: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(30) }
    val packages = records.map { it.packageName }.distinct()
    val shown = records.filter { filter.isEmpty() || it.packageName == filter }
    Page {
        Eyebrow("이 기기에 모아둔 소식")
        Text("받은 알림", style = MaterialTheme.typography.headlineLarge)
        Text("총 ${records.size}개 · 14일 동안 보관해요", color = Clay.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(filter.isEmpty(), { filter = ""; limit = 30 }, label = { Text("전체") })
            packages.forEach { pkg -> FilterChip(filter == pkg, { filter = pkg; limit = 30 }, label = { Text(SourceCatalog.label(pkg).take(8)) }) }
        }
        if (shown.isEmpty()) EmptyCard("아직 모아둔 알림이 없어요", "연결한 앱에 새 알림이 오면 여기에 보여요.")
        else shown.take(limit).forEach { RecordCard(it) { onRecord(it) } }
        if (shown.size > limit) TextButton(onClick = { limit += 30 }) { Text("더 보기") }
        ClayButton("연구자료 검토하고 저장", enabled = records.isNotEmpty(), primary = false, onClick = onExport)
    }
}

@Composable
fun DetailScreen(record: ProbeRecord, alreadyRemembered: Boolean = false, childProfile: ChildNoticeProfile = ChildNoticeProfile(), onBack: () -> Unit, onDelete: () -> Unit,
                 onSource: () -> Unit, onRemember: (String, Long?, Long?) -> Unit = { _, _, _ -> }) {
    var fields by rememberSaveable(record.id) { mutableStateOf(false) }
    var confirmedDueAt by rememberSaveable(record.id) { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val decision = remember(record, childProfile) { NoticeDecisionEngine.decide(record, childProfile) }
    val candidate = remember(record, childProfile) { NotificationCandidateParser.parse(record, childProfile) }
    fun taskText(): String = candidate?.let { candidateTaskText(record, it) } ?: record.title.ifBlank { "알림 확인" }
    fun chooseDueTime() {
        val initial = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
            timeInMillis = candidate?.dueAt ?: (System.currentTimeMillis() + 86_400_000L)
        }
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                confirmedDueAt = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul")).apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show()
        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
    }
    Page {
        BackHeading("받은 알림", onBack)
        StatusPill(record.appLabel)
        Text(record.title.ifBlank { "제목 없는 알림" }, style = MaterialTheme.typography.headlineMedium)
        Text("${displayTime(record.receivedAt)} 받았어요", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
        if (record.truncated) Text("긴 알림의 일부만 저장됐어요. 전체 내용은 원래 앱에서 확인해주세요.", color = Clay.Error, style = MaterialTheme.typography.bodyMedium)
        ClayCard(tint = Clay.Sage) {
            val body = record.bigText.ifBlank { record.text }.ifBlank { record.textLines.joinToString("\n") }
            SelectionContainer { Text(body.ifBlank { "앱이 알림 내용을 보내주지 않았어요. 원래 앱에서 직접 확인해주세요." }, style = MaterialTheme.typography.bodyLarge) }
        }
        decision.action?.let { action ->
            AgentCard {
                StatusPill(when {
                    decision.applicability == NoticeApplicability.INELIGIBLE -> "대상 아님"
                    decision.obligation == NoticeObligation.OPTIONAL_OPPORTUNITY -> "선택 활동"
                    alreadyRemembered -> "기억 중"
                    else -> "근거 확인"
                })
                Text(action.label, style = MaterialTheme.typography.titleMedium)
                Text(decision.applicabilityReason, color = Clay.Muted)
                action.whenText?.let { Text("원문 날짜 표현: $it", color = Clay.CoralDark) }
                action.dueAt?.let { Text("정확한 시각: ${displayTime(it)}", color = Clay.Green) }
                if (decision.issues.isNotEmpty()) Text(decision.issues.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                val canRemember = action.required && !alreadyRemembered
                Text(when {
                    decision.obligation == NoticeObligation.OPTIONAL_OPPORTUNITY -> "관심을 표시하기 전에는 신청 부탁이나 마감 알림을 만들지 않아요."
                    alreadyRemembered -> "같은 원문에서 만든 부탁은 중복으로 만들지 않아요."
                    action.dueAt == null -> "시각이 분명하지 않아 자동 알림 없이 원문 확인이 필요해요."
                    else -> "필요하면 확인한 시각으로 부탁에 저장할 수 있어요."
                }, style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                if (action.required) {
                    AgentButton(if (alreadyRemembered) "이미 모모가 기억하고 있어요" else "날짜 확인하고 모모에게 맡기기", enabled = canRemember, onClick = ::chooseDueTime)
                } else {
                    OutlinedButton(onClick = onSource, modifier = Modifier.fillMaxWidth()) { Text("원문에서 살펴보기") }
                }
            }
        }
        ClayButton("원래 앱에서 확인", primary = false, onClick = onSource)
        TextButton(onClick = { fields = !fields }) { Text(if (fields) "수집 내용 접기" else "수집한 내용 모두 보기") }
        if (fields) {
            listOf("제목" to record.title, "짧은 본문" to record.text, "펼친 본문" to record.bigText,
                "본문 줄" to record.textLines.joinToString("\n"), "추가 설명" to record.subText.orEmpty(), "묶음 설명" to record.summaryText.orEmpty(),
                "원래 게시 시각" to displayTime(record.postedAt)).forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, color = Clay.Green, style = MaterialTheme.typography.labelLarge); SelectionContainer { Text(value.ifBlank { "내용 없음" }, color = Clay.Muted) } }
            }
        }
        Text("원문은 기기 안에서만 보관하고 규칙으로 챙길 후보를 찾아요. 자동 확정하거나 외부로 보내지 않아요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Clay.Error)) { Text("이 알림 삭제") }
    }
    confirmedDueAt?.let { dueAt ->
        val zone = ZoneId.of("Asia/Seoul")
        val previousEvening = Instant.ofEpochMilli(dueAt).atZone(zone).minusDays(1).withHour(20).withMinute(0).withSecond(0).toInstant().toEpochMilli()
        val oneHourBefore = dueAt - 3_600_000L
        AlertDialog(
            onDismissRequest = { confirmedDueAt = null },
            title = { Text("언제쯤 다시 알려드릴까요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("확인한 기한: ${displayTime(dueAt)}", color = Clay.Green)
                    Text("휴대폰 절전 상태에 따라 몇 분 늦게 도착할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                    OutlinedButton(onClick = { onRemember(taskText(), dueAt, previousEvening); confirmedDueAt = null }, enabled = previousEvening > System.currentTimeMillis(), modifier = Modifier.fillMaxWidth()) { Text("전날 오후 8시 무렵") }
                    OutlinedButton(onClick = { onRemember(taskText(), dueAt, oneHourBefore); confirmedDueAt = null }, enabled = oneHourBefore > System.currentTimeMillis(), modifier = Modifier.fillMaxWidth()) { Text("1시간 전쯤") }
                    OutlinedButton(onClick = { onRemember(taskText(), dueAt, dueAt); confirmedDueAt = null }, enabled = dueAt > System.currentTimeMillis(), modifier = Modifier.fillMaxWidth()) { Text("기한 무렵") }
                }
            },
            confirmButton = { TextButton(onClick = { onRemember(taskText(), dueAt, null); confirmedDueAt = null }) { Text("알림 없이 부탁만") } },
            dismissButton = { TextButton(onClick = { confirmedDueAt = null }) { Text("취소") } },
        )
    }
}

internal fun candidateTaskText(record: ProbeRecord, candidate: kr.mom.probe.data.NotificationCandidate): String {
    return kr.mom.probe.task.CandidateActionPlanner.taskText(record, candidate)
}

@Composable
fun SettingsScreen(settings: ProbeSettings, access: Boolean, connected: Boolean, recordCount: Int, busy: Boolean,
                   onSources: () -> Unit, onChild: () -> Unit, onToggle: (Boolean) -> Unit,
                   onPermission: () -> Unit, onTest: () -> Unit, onDelete: () -> Unit, onPolicy: () -> Unit,
                   onReminders: () -> Unit = {}, onWidget: () -> Unit = {},
                   assistantAlertsEnabled: Boolean = false, onAssistantAlerts: (Boolean) -> Unit = {}) {
    var details by rememberSaveable { mutableStateOf(false) }
    Page {
        Eyebrow("엄마가 정하는 범위")
        Text("설정", style = MaterialTheme.typography.headlineLarge)
        ClayCard(tint = Clay.Sage) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("알림 모으기", style = MaterialTheme.typography.titleMedium); Text(if (settings.collectionEnabled) "선택한 앱의 새 알림만 모아요" else "지금은 쉬고 있어요", style = MaterialTheme.typography.bodySmall, color = Clay.Muted) }
                Switch(settings.collectionEnabled, onCheckedChange = onToggle, enabled = !busy)
            }
            Text("끄면 새 알림 수집이 멈춰요.\n이미 모은 알림은 그대로 남아 있어요.", color = Clay.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.fillMaxWidth().flatSurface().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("긴급한 일만 바로 알림", style = MaterialTheme.typography.titleMedium)
                    Text("오늘 안에 놓치면 안 되는 일만 바로 알려줘요", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
                }
                Switch(assistantAlertsEnabled, onCheckedChange = onAssistantAlerts, enabled = !busy)
            }
            Text("잠금화면에는 공지 제목과 내용을 숨겨요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        ClayCard {
            SettingLink("연결할 앱과 사이트", "앱 ${settings.selectedPackages.size}개 · 사이트 연결 관리", onSources)
            HorizontalDivider(color = Color.White)
        SettingLink("자녀 정보", listOfNotNull(settings.childName.ifBlank { null }, settings.schoolName.ifBlank { null }, settings.schoolLevel?.label, settings.schoolGrade?.let { "$it 학년" }).joinToString(" · ").ifBlank { "아직 입력 전" }, onChild)
            HorizontalDivider(color = Color.White)
            SettingLink("비서 알림", "시간을 정하고 소식 듣기", onReminders)
            HorizontalDivider(color = Color.White)
            SettingLink("홈 화면 비서", "모모를 홈 화면에 놓기", onWidget)
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "도움말 접기" else "도움말 · 연결 문제 해결") }
        if (details) ClayCard {
            SettingLink("알림 읽기", if (access) "허용됨" else "설정 필요", onPermission)
            Text(if (!access) "알림 읽기가 꺼져 있어요" else if (connected) "알림을 받을 준비가 됐어요" else "휴대폰과 연결을 준비하고 있어요", style = MaterialTheme.typography.titleMedium)
            Text("저장된 알림 ${recordCount}개\n선택한 앱: ${settings.selectedPackages.joinToString(" · ") { SourceCatalog.label(it) }.ifBlank { "없음" }}", style = MaterialTheme.typography.bodyMedium, color = Clay.Muted)
            TextButton(onClick = onTest) { Text("테스트 알림 보내기") }
            Text("이 버튼은 앱의 알림 보내기만 확인해요. 학교 앱 수집 성공을 뜻하지 않아요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        SettingLink("개인정보 및 데이터", "보관 범위와 삭제", onPolicy)
        Text("이번 테스트는 무료예요.\nAI 분석 미연결 · 이 기기에서 기본 정리만 해요. 원문은 14일 뒤 삭제하고, 자동으로 외부에 보내지 않아요.", style = MaterialTheme.typography.bodyMedium, color = Clay.Muted)
        
        Text("나는 엄마다 · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
    }
}

@Composable
private fun SettingLink(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(value, color = Clay.Muted, style = MaterialTheme.typography.bodySmall) }
        Text("›", fontSize = 24.sp, color = Clay.Muted)
    }
}

@Composable
fun PolicyContent(onDelete: (() -> Unit)? = null) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("선택한 앱의 소식을 모아 확인하고, 기기 안의 규칙으로 준비물·제출·마감 후보를 찾는 비서 시험판입니다.")
        Text("웹 연결: 직접 입력한 로그인 정보는 공식 사이트에 전송됩니다. 쿠키와 웹 저장소는 이 앱의 WebView에 남을 수 있어요. 인증 갱신·개인 공지 자동 조회는 아직 지원 전입니다.")
        Text("모모와 부탁: 질문은 이 기기에 저장된 알림·부탁 안에서만 답합니다. 명확하고 되돌릴 수 있는 저장 명령은 바로 암호화 저장하고 화면에서 취소할 수 있습니다. 정한 기한과 다시 알릴 시각도 이 기기에만 저장합니다.")
        Text("캘린더: 엄마가 직접 고른 쓰기 가능한 캘린더 ID와 계정 이름을 이 기기에 암호화해 보관합니다. 일정 저장 성공은 휴대폰 CalendarProvider에서 다시 읽어 확인한 결과이며, 계정 서버 동기화나 다른 기기 반영 완료를 뜻하지 않습니다.")
        Text("브리핑과 음성: 알림 시간은 이 기기에 저장합니다. 소리로 듣기를 누르면 휴대폰의 음성 엔진에 부탁 문장을 전달하며, 엔진 설정에 따라 네트워크를 사용할 수 있어요.")
        onDelete?.let { delete -> TextButton(onClick = delete) { Text("참여 종료 · 전체 데이터 삭제", color = Clay.Error) } }
        Text("수집: 직접 선택한 앱의 새 알림 제목·본문·게시 시각·앱 정보, 아이 이름/별칭·학교·학년. 다른 앱 내용은 저장하지 않습니다.")
        Text("보관: Android 보안 키로 암호화하여 이 휴대폰에만 저장합니다. 원문은 받은 날부터 14일, 아이 이름과 설정은 참여 종료까지 보관합니다. 휴대폰이 꺼져 있으면 다음 실행 시 만료 자료를 정리합니다.")
        Text("외부 연결: 학교 홈페이지 공지는 공개 게시판에서만 확인하며, 학교명은 외부 서비스로 보내지 않습니다. 부모 계정 비밀번호는 보관하지 않습니다. 일반 ChatGPT 로그인은 이 앱의 API 사용권이 아니며, 알림 원문은 서버·AI·광고 서비스로 자동 전송하지 않습니다.")
        Text("통제: 언제든 앱 선택을 바꾸거나 수집을 멈출 수 있습니다. 개별 알림 또는 전체 정보를 삭제할 수 있습니다. 전체 삭제는 동의와 암호화 키, 캘린더·알람 앱 선택 기록과 실행 기록까지 제거합니다.")
        Text("제한: 앱 안의 공지·첨부파일 전체를 읽지 않습니다. 후보를 자동 확정하거나 AI 서버로 보내지 않으며, 결제도 하지 않습니다. 앱 삭제/재설치/기기 변경 시 복구하지 않습니다. 이미 외부에 저장한 파일과 외부 캘린더 일정은 앱에서 회수할 수 없습니다.")
    }
}
