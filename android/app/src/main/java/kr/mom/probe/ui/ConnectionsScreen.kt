package kr.mom.probe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kr.mom.probe.connector.*
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.ProbeSettings
import kr.mom.probe.data.SchoolLevel
import kr.mom.probe.sync.SourceConfigs
import kr.mom.probe.sync.SourceIds
import kr.mom.probe.sync.SourceSyncSnapshot
import kr.mom.probe.sync.SourceSyncStatus

@Composable
fun ChildProfileScreen(settings: ProbeSettings, busy: Boolean,
                       onSave: (String, String, Int?, SchoolLevel?) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(settings.childName) { mutableStateOf(settings.childName) }
    var school by rememberSaveable(settings.schoolName) { mutableStateOf(settings.schoolName) }
    var grade by rememberSaveable(settings.schoolGrade) { mutableStateOf(settings.schoolGrade) }
    var level by rememberSaveable(settings.schoolLevel, settings.schoolName) { mutableStateOf(settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName).takeIf { it != SchoolLevel.UNKNOWN }) }
    var gradeMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    val cleanSchool = school.trim()
    val validName = name.trim().length in 1..20
    val validSchool = cleanSchool.isEmpty() || cleanSchool.length in 2..60
    val maxGrade = if (level == SchoolLevel.MIDDLE) 3 else 6
    val valid = validName && validSchool && grade?.let { it in 1..maxGrade } != false
    Page {
        BackHeading("자녀 정보", onBack)
        Eyebrow("엄마 비서의 첫 번째 가족")
        Text("누구의 소식을\n챙기면 될까요?", style = MaterialTheme.typography.headlineLarge)
        Text("이름이나 별칭만으로 시작할 수 있어요. 학교와 학년은 나이스 공개 일정을 연결할 때만 입력해주세요.", color = Clay.Muted)
        OutlinedTextField(name, { if (it.length <= 30) name = it }, Modifier.fillMaxWidth(),
            label = { Text("아이 이름 또는 별칭") }, singleLine = true, shape = RoundedCornerShape(22.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(school, { if (it.length <= 70) school = it }, Modifier.fillMaxWidth(),
            label = { Text("학교 정식 이름 (선택)") }, placeholder = { Text("예: 성남정자초등학교") }, singleLine = true,
            shape = RoundedCornerShape(22.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            isError = school.isNotBlank() && !validSchool,
            supportingText = { Text(if (school.isNotBlank() && !validSchool) "학교 이름을 2~60자로 입력해주세요." else "간단히 적어도 연결할 때 나이스 공식 이름으로 확인해요.") })
        Box {
            OutlinedButton(onClick = { levelMenu = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(22.dp)) {
                Text(level?.label ?: "학교급 선택 (선택)", Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(levelMenu, { levelMenu = false }, Modifier.fillMaxWidth(.72f)) {
                DropdownMenuItem(text = { Text("나중에 입력") }, onClick = { level = null; levelMenu = false })
                DropdownMenuItem(text = { Text(SchoolLevel.ELEMENTARY.label) }, onClick = { level = SchoolLevel.ELEMENTARY; levelMenu = false })
                DropdownMenuItem(text = { Text(SchoolLevel.MIDDLE.label) }, onClick = {
                    level = SchoolLevel.MIDDLE
                    if ((grade ?: 0) > 3) grade = null
                    levelMenu = false
                })
            }
        }
        Box {
            OutlinedButton(onClick = { gradeMenu = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(22.dp)) {
                Text(grade?.let { "$it 학년" } ?: "학년 선택 (선택)", Modifier.weight(1f)); Text("⌄")
            }
            DropdownMenu(gradeMenu, { gradeMenu = false }, Modifier.fillMaxWidth(.72f)) {
                DropdownMenuItem(text = { Text("나중에 입력") }, onClick = { grade = null; gradeMenu = false })
                (1..maxGrade).forEach { value -> DropdownMenuItem(text = { Text("$value 학년") }, onClick = { grade = value; gradeMenu = false }) }
            }
        }
        ClayCard(tint = Clay.Sage) {
            Text("자녀 정보는 이 휴대폰에 암호화해 저장해요.", style = MaterialTheme.typography.titleMedium)
            Text("사이트가 추가 인증을 요구하면 공식 화면에서 엄마가 직접 확인해요. 비밀번호는 우리 앱에 저장하지 않아요.", color = Clay.Muted)
        }
        ClayButton(if (busy) "저장하고 있어요…" else "자녀 정보 저장", enabled = valid && !busy) {
            onSave(name.trim(), cleanSchool, grade, level)
        }
    }
}

@Composable
fun ConnectionsScreen(
    settings: ProbeSettings,
    installedApps: List<SourceApp>,
    missingApps: List<SourceApp>,
    verifiedAppPackages: Set<String>,
    connectorState: ConnectorState,
    busy: Boolean,
    onBack: () -> Unit,
    onToggleApp: (String, Boolean) -> Unit,
    onConnectNeis: () -> Unit,
    onDisconnectNeis: () -> Unit,
    sourceSnapshots: Map<String, SourceSyncSnapshot> = emptyMap(),
    onRefreshSource: (String) -> Unit = {},
    onRecommendApp: (SourceApp) -> Unit,
    onSkip: () -> Unit = {},
    listenerAccess: Boolean = true,
    appPopupOn: Map<String, Boolean?> = emptyMap(),
    onOpenAppNotifications: (String) -> Unit = {},
    onOpenWebsite: (String) -> Unit = {},
    postingHints: Map<String, String> = emptyMap(),
) {
    val publicConnection = connectorState.sites[SourceIds.NEIS_PUBLIC]
    val schoolLevel = settings.schoolLevel ?: NoticeDecisionEngine.inferLevel(settings.schoolName)
    val normalizedSchool = settings.schoolName.replace(" ", "")
    val schoolWebsiteAvailable = normalizedSchool == "성남정자초등학교" &&
        schoolLevel == SchoolLevel.ELEMENTARY && settings.schoolGrade in 1..6
    Page {
        BackHeading("연결", onBack)
        Text("제가 확인할 곳을\n골라주세요.", style = MaterialTheme.typography.headlineMedium)
        Text("켜둔 곳만 확인해요.", color = Clay.Muted)

        ConnectionSection("앱") {
            if (installedApps.isEmpty()) ConnectionEmptyRow("연결할 수 있는 앱이 없어요")
            installedApps.forEach { app ->
                val enabled = app.packageName in settings.selectedPackages
                val popupOn = appPopupOn[app.packageName]
                ConnectionSwitchRow(
                    mark = app.mark,
                    name = app.name,
                    status = when {
                        enabled && popupOn == false -> "이 앱의 알림이 꺼져 있어요 · 켜야 가져올 수 있어요"
                        enabled && !listenerAccess -> "알림 읽기 권한이 꺼져 있어요"
                        enabled && app.packageName in verifiedAppPackages -> "알림 수신 이력 있음"
                        enabled -> "첫 알림 기다리는 중"
                        else -> "꺼짐"
                    },
                    checked = enabled,
                    enabled = !busy,
                    onCheckedChange = { onToggleApp(app.packageName, it) },
                )
                if (enabled && popupOn == false) {
                    TextButton(onClick = { onOpenAppNotifications(app.packageName) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("${app.name} 알림 설정 열기")
                    }
                }
            }
        }

        if (missingApps.isNotEmpty()) {
            ConnectionSection("설치하지 않은 후보") {
                missingApps.forEach { app ->
                    ConnectionSuggestionRow(app) { onRecommendApp(app) }
                }
                Text("설치한 뒤 이 화면에서 직접 켜면 알림을 확인할 수 있어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
            }
        }

        ConnectionSection("사이트") {
            if (schoolWebsiteAvailable) {
                SourceStatusRow(
                    mark = "정",
                    name = SourceConfigs.get(SourceIds.SCHOOL_WEBSITE)?.label ?: "성남정자초 공식 홈페이지",
                    sourceId = SourceIds.SCHOOL_WEBSITE,
                    snapshot = sourceSnapshots[SourceIds.SCHOOL_WEBSITE],
                    enabled = !busy && settings.onboardingDone,
                    onRefresh = { onRefreshSource(SourceIds.SCHOOL_WEBSITE) },
                    hint = postingHints[SourceIds.SCHOOL_WEBSITE],
                )
                TextButton(onClick = { onOpenWebsite(SourceIds.SCHOOL_WEBSITE) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("홈페이지 열기")
                }
                HorizontalDivider(color = Color.White.copy(alpha = .8f))
            }
            ConnectionSwitchRow(
                mark = "N",
                name = "나이스 학교정보",
                status = if (publicConnection?.status == ConnectionStatus.CONNECTED) sourceStatusText(sourceSnapshots[SourceIds.NEIS_PUBLIC]) else "학교 일정 · sourceId ${SourceIds.NEIS_PUBLIC}",
                checked = publicConnection?.status == ConnectionStatus.CONNECTED,
                enabled = !busy && settings.schoolName.isNotBlank(),
                onCheckedChange = { checked -> if (checked) onConnectNeis() else onDisconnectNeis() },
            )
            if (publicConnection?.status == ConnectionStatus.CONNECTED) {
                TextButton(onClick = { onRefreshSource(SourceIds.NEIS_PUBLIC) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("나이스 지금 확인")
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = .8f))
            ConnectionSwitchRow("N+", "나이스 학부모서비스", "개인 공지 조회 연동 미지원", false, false) { }
            HorizontalDivider(color = Color.White.copy(alpha = .8f))
        }
        Text("앱과 웹이 같은 서비스이면 앱만 표시해요. 학교 공식 홈페이지와 나이스처럼 앱과 역할이 다른 사이트만 따로 보여줘요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (!settings.onboardingDone) TextButton(onClick = onSkip, enabled = !busy) { Text("연결은 나중에 · 비서 만나기") }
    }
}

private fun sourceStatusText(snapshot: SourceSyncSnapshot?): String = when (snapshot?.status) {
    SourceSyncStatus.FETCHED -> "조회 완료 · 저장 ${snapshot.storedCount}개"
    SourceSyncStatus.SUCCESS_EMPTY -> "정상 응답 · 새 소식 0개"
    SourceSyncStatus.PARTIAL -> "일부 확인 · ${snapshot.message ?: "미확인 범위 있음"}"
    SourceSyncStatus.AUTH_REQUIRED -> "다시 로그인 필요"
    SourceSyncStatus.UNSUPPORTED -> "조회 구현 연결 전"
    SourceSyncStatus.OFFLINE -> "오프라인 · 마지막 자료 유지"
    SourceSyncStatus.ERROR -> "확인 필요 · ${snapshot.message ?: "오류"}"
    SourceSyncStatus.RUNNING -> "확인 중"
    SourceSyncStatus.NEVER, null -> "아직 확인 전"
}

@Composable
private fun ConnectionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Clay.Muted)
        Column(Modifier.fillMaxWidth().claySurface(radius = 24.dp).padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun ConnectionSwitchRow(mark: String, name: String, status: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectorMark(mark, if (checked) Clay.Sage else Clay.Background)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (checked) Clay.Green else Clay.Muted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SourceStatusRow(mark: String, name: String, sourceId: String, snapshot: SourceSyncSnapshot?, enabled: Boolean, onRefresh: () -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectorMark(mark, Clay.Sage)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text("${sourceStatusText(snapshot)} · sourceId $sourceId", style = MaterialTheme.typography.bodySmall, color = Clay.Green)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        TextButton(onClick = onRefresh, enabled = enabled) { Text("지금") }
    }
}

@Composable
private fun ConnectionEmptyRow(text: String) {
    Text(text, modifier = Modifier.padding(vertical = 20.dp), color = Clay.Muted, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ConnectionSuggestionRow(app: SourceApp, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ConnectorMark(app.mark, Clay.Background)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, style = MaterialTheme.typography.titleMedium)
            Text(app.category, style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
        }
        Text("설치 보기", style = MaterialTheme.typography.labelLarge, color = Clay.Green)
    }
}

@Composable private fun ConnectorMark(text: String, tint: Color) {
    Box(Modifier.size(48.dp).claySurface(tint, 16.dp), contentAlignment = Alignment.Center) { Text(text, fontWeight = FontWeight.Bold, color = Clay.Ink) }
}
