package kr.mom.probe.reminder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.mom.probe.ui.BellMascot
import kr.mom.probe.ui.Clay
import kr.mom.probe.ui.minTouchTarget

data class AlarmContentState(
    val title: String,
    val dateText: String,
    val scheduledTimeText: String,
    val actionTitle: String,
    val actionSummary: String,
    val detailLines: List<String> = emptyList(),
    val locked: Boolean = false,
    val snoozeEnabled: Boolean = true,
    val completeEnabled: Boolean = false,
    val showComplete: Boolean = false,
    val showSpeak: Boolean = false,
    val statusText: String? = null,
    val timeLabel: String = "예약 시각",
)

@Composable
fun AlarmContent(
    state: AlarmContentState,
    onStopSound: () -> Unit,
    onSnoozeTenMinutes: () -> Unit,
    onShowDetails: () -> Unit,
    onComplete: () -> Unit = {},
    onUnlock: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
    onAskMomo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var details by remember(state.detailLines) { mutableStateOf(false) }
    val safeActionTitle = if (state.locked) "잠금을 풀고 확인하세요" else state.actionTitle
    val safeActionSummary = if (state.locked) "모모가 알려드릴 소식이 있어요" else state.actionSummary
    val safeStatus = if (state.locked) null else state.statusText
    val safeDetailLines = if (state.locked) emptyList() else state.detailLines
    Surface(modifier.fillMaxSize(), color = Clay.Background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth <= 340.dp
            val outerGap = if (compact) 8.dp else 12.dp
            val bodyGap = if (compact) 8.dp else 12.dp
            val horizontalPadding = if (compact) 16.dp else 20.dp
            val verticalPadding = if (compact) 12.dp else 18.dp
            val paperPadding = if (compact) 12.dp else 18.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalArrangement = Arrangement.spacedBy(outerGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(bodyGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.dateText,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Clay.Green,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (!compact) BellMascot(Modifier.size(width = 44.dp, height = 52.dp).testTag("alarm-mascot"))
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val clockSize = if (compact) 44.sp else if (maxWidth < 360.dp) 52.sp else 64.sp
                    Text(
                        state.scheduledTimeText,
                        modifier = Modifier.testTag("alarm-time"),
                        color = Clay.Ink,
                        fontSize = clockSize,
                        lineHeight = (clockSize.value + 4).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(state.timeLabel, color = Clay.Green, style = MaterialTheme.typography.bodyMedium)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Clay.Paper, RoundedCornerShape(8.dp))
                        .padding(paperPadding),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        safeActionTitle,
                        modifier = Modifier.testTag("alarm-action"),
                        color = Clay.Ink,
                        fontSize = if (compact) 22.sp else 28.sp,
                        lineHeight = if (compact) 28.sp else 36.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = if (state.locked) 3 else if (compact) 5 else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        safeActionSummary,
                        color = Clay.Muted,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                safeStatus?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().testTag("alarm-status"),
                        color = if (it.contains("못")) Clay.Error else Clay.Green,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                if (details && safeDetailLines.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Clay.Sage.copy(alpha = .72f), RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        safeDetailLines.forEach { line ->
                            Text(line, color = Clay.Ink, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = onStopSound,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("alarm-stop"),
                    colors = ButtonDefaults.buttonColors(containerColor = Clay.CoralDark),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("소리 끄기", style = MaterialTheme.typography.labelLarge) }
                Button(
                    onClick = onSnoozeTenMinutes,
                    enabled = state.snoozeEnabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp).testTag("alarm-snooze"),
                    colors = ButtonDefaults.buttonColors(containerColor = Clay.Green),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("10분 뒤 다시 알림", style = MaterialTheme.typography.labelLarge) }
            if (state.locked && onUnlock != null) {
                OutlinedButton(
                    onClick = onUnlock,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("alarm-unlock"),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("잠금 풀고 확인") }
            }
            if (!state.locked) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            details = true
                            onShowDetails()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 54.dp)
                            .semantics { contentDescription = "내용 보기" }
                            .testTag("alarm-details"),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(if (compact) "내용" else "내용 보기", maxLines = 1) }
                    if (state.showComplete) {
                        OutlinedButton(
                            onClick = onComplete,
                            enabled = state.completeEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 54.dp)
                                .semantics { contentDescription = "준비 완료" }
                                .testTag("alarm-complete"),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(if (compact) "완료" else "준비 완료", maxLines = 1) }
                    }
                }
                if (!state.locked && state.showSpeak && onSpeak != null) {
                    TextButton(onClick = onSpeak, modifier = Modifier.minTouchTarget().testTag("alarm-speak")) { Text("소리로 듣기") }
                }
            }
            if (onAskMomo != null) {
                TextButton(
                    onClick = onAskMomo,
                    modifier = Modifier
                        .minTouchTarget()
                        .semantics { contentDescription = "할 일 열기" }
                        .testTag("alarm-ask-momo"),
                ) { Text("할 일 열기") }
            }
            }
        }
        }
    }
}
