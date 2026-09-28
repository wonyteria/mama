package kr.mom.probe.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kr.mom.probe.agent.AgentIdentity
import kr.mom.probe.agent.AgentIntent
import kr.mom.probe.agent.CapturePlan
import kr.mom.probe.agent.CaptureDisposition
import kr.mom.probe.agent.LocalAgentContext
import kr.mom.probe.agent.LocalAgentEngine
import kr.mom.probe.data.NoticeDecisionEngine
import kr.mom.probe.data.NoticeGrouping
import kr.mom.probe.data.ProbeRepository
import kr.mom.probe.task.AssistantTaskStore
import kr.mom.probe.ui.AgentMascot
import kr.mom.probe.ui.AgentMascotState
import kr.mom.probe.ui.Clay
import kr.mom.probe.ui.MomTheme
import kr.mom.probe.ui.minTouchTarget

/**
 * Structured local voice capture entry point. Non-exported, explicit
 * intent only (widget/home/tile callers arrive through [intent]).
 *
 * Guarantees: nothing listens on create/resume/recreate; RECORD_AUDIO is
 * requested only after an explicit mic tap; rotation or destruction cancels
 * and destroys the session. No audio is ever stored — the transcript is
 * memory-only until the parent explicitly confirms the preview card.
 * Saves route through [VoiceCaptureSaver]; nothing writes on question,
 * clarification, or discard paths.
 */
class VoiceQuickCaptureActivity : ComponentActivity() {

    private lateinit var controller: VoiceCaptureController
    private var captureState by mutableStateOf(VoiceCaptureState.IDLE)
    private var transcript by mutableStateOf("")
    private var plan by mutableStateOf<CapturePlan?>(null)
    private var errorText by mutableStateOf<String?>(null)
    private var needsFallbackConsent by mutableStateOf(false)
    private var permissionDenied by mutableStateOf(false)
    private var captureId = VoiceCaptureStore.newCaptureId()

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onPermissionResult(granted) }

    /**
     * Runtime-permission result handling, kept in one place so tests can
     * drive the same decision path the launcher callback uses.
     */
    internal fun onPermissionResult(granted: Boolean) {
        if (granted) {
            permissionDenied = false
            controller.startCapture()
        } else {
            permissionDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = VoiceCaptureController {
            (factoryOverride ?: ::SystemSpeechRecognizerFactory)(this)
        }
        controller.classifier = classifier@{ text ->
            (classifierOverride?.invoke(this, text)) ?: runCatching {
                LocalAgentEngine().capture(text, buildContext())
            }.getOrNull()
        }
        controller.onChanged = {
            captureState = controller.state
            transcript = controller.transcript
            plan = controller.plan
            errorText = controller.errorText
            needsFallbackConsent = controller.needsFallbackConsent
        }
        // Pre-warm the stores off the main thread so classification/saving
        // see loaded state; missing consent fails the write path honestly.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { AssistantTaskStore.get(this@VoiceQuickCaptureActivity).load() }
            runCatching { VoiceCaptureStore.get(this@VoiceQuickCaptureActivity).load() }
        }
        setContent {
            MomTheme {
                VoiceCaptureScreen(
                    state = captureState,
                    transcript = transcript,
                    plan = plan,
                    errorText = errorText,
                    permissionDenied = permissionDenied,
                    needsFallbackConsent = needsFallbackConsent,
                    onMicTap = ::onMicTap,
                    onConsentContinue = { controller.onFallbackConsent(true) },
                    onConsentCancel = { controller.onFallbackConsent(false) },
                    onSave = ::onSave,
                    onSaveMemo = { controller.convertToMemo() },
                    onDiscard = { controller.discard() },
                    onClose = ::finish,
                )
            }
        }
    }

    private fun buildContext(): LocalAgentContext {
        val repository = ProbeRepository.get(this)
        val settings = repository.settings.value
        return LocalAgentContext(
            childName = settings.childName,
            notifications = repository.records.value,
            tasks = AssistantTaskStore.get(this).tasks.value,
            childProfile = NoticeDecisionEngine.childProfile(settings),
            institution = NoticeGrouping.institution(settings),
        )
    }

    private fun onMicTap() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionDenied = false
            when (captureState) {
                VoiceCaptureState.LISTENING -> controller.stopCapture()
                VoiceCaptureState.SAVING, VoiceCaptureState.THINKING -> Unit
                else -> {
                    captureId = VoiceCaptureStore.newCaptureId()
                    controller.startCapture()
                }
            }
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onSave() {
        val current = plan ?: return
        if (!controller.requestSave()) return
        val saver = saverOverride ?: LocalVoiceCaptureSaver(this)
        lifecycleScope.launch {
            when (val result = saver.save(current, transcript, captureId)) {
                VoiceSaveResult.Saved -> controller.finishSave(true)
                is VoiceSaveResult.Failed -> controller.finishSave(false, result.message)
            }
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    companion object {
        /** Stable explicit-intent contract for widget/home/tile entry. */
        fun intent(context: Context): Intent =
            Intent(context, VoiceQuickCaptureActivity::class.java)

        /** Test seam: unit/Robolectric tests swap in a fake factory. */
        var factoryOverride: ((Context) -> SpeechRecognizerFactory)? = null
        /** Test seam: replaces LocalAgentEngine classification. */
        var classifierOverride: ((Context, String) -> CapturePlan?)? = null
        /** Test seam: replaces the durable write. */
        var saverOverride: VoiceCaptureSaver? = null
    }
}

private fun mascotFor(state: VoiceCaptureState): AgentMascotState = when (state) {
    VoiceCaptureState.IDLE -> AgentMascotState.IDLE
    VoiceCaptureState.LISTENING -> AgentMascotState.LISTENING
    VoiceCaptureState.THINKING, VoiceCaptureState.SAVING -> AgentMascotState.THINKING
    VoiceCaptureState.DONE, VoiceCaptureState.SAVED -> AgentMascotState.DONE
    VoiceCaptureState.ERROR -> AgentMascotState.NEW_INFO
}

private fun headlineFor(state: VoiceCaptureState): String = when (state) {
    VoiceCaptureState.IDLE -> "마이크를 눌러 말해주세요"
    VoiceCaptureState.LISTENING -> "듣고 있어요"
    VoiceCaptureState.THINKING -> "정리하고 있어요"
    VoiceCaptureState.DONE -> "이렇게 들었어요"
    VoiceCaptureState.ERROR -> "다시 시도해 주세요"
    VoiceCaptureState.SAVING -> "저장하고 있어요"
    VoiceCaptureState.SAVED -> "저장했어요"
}

@Composable
internal fun VoiceCaptureScreen(
    state: VoiceCaptureState,
    transcript: String,
    plan: CapturePlan?,
    errorText: String?,
    permissionDenied: Boolean,
    needsFallbackConsent: Boolean,
    onMicTap: () -> Unit,
    onConsentContinue: () -> Unit,
    onConsentCancel: () -> Unit,
    onSave: () -> Unit,
    onSaveMemo: () -> Unit,
    onDiscard: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Clay.Background)) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AgentMascot(
                state = mascotFor(state),
                decorative = false,
                modifier = Modifier.size(96.dp, 112.dp).testTag("voice-mascot"),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                if (permissionDenied) "마이크 권한이 필요해요" else headlineFor(state),
                style = MaterialTheme.typography.titleMedium,
                color = Clay.Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("voice-headline"),
            )
            Spacer(Modifier.height(10.dp))
            when {
                permissionDenied -> PermissionDeniedBody(onMicTap)
                needsFallbackConsent -> FallbackConsentBody(onConsentContinue, onConsentCancel)
                else -> CaptureBody(
                    state, transcript, plan, errorText,
                    onMicTap, onSave, onSaveMemo, onDiscard,
                )
            }
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onClose, modifier = Modifier.minTouchTarget()) {
                Text("닫기", color = Clay.Muted)
            }
        }
    }
}

@Composable
private fun PermissionDeniedBody(onMicTap: () -> Unit) {
    Text(
        "${AgentIdentity.displayName}가 들으려면 마이크 권한이 필요해요. " +
            "허용하면 버튼을 누를 때만 듣고, 녹음 파일은 저장하지 않아요. " +
            "거절했다면 설정 > 애플리케이션 > MAMA > 권한에서 마이크를 켤 수 있어요.",
        style = MaterialTheme.typography.bodyMedium,
        color = Clay.Muted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Button(onClick = onMicTap, modifier = Modifier.minTouchTarget().testTag("voice-permission-retry")) {
        Text("권한 허용하기")
    }
}

@Composable
private fun FallbackConsentBody(onContinue: () -> Unit, onCancel: () -> Unit) {
    Text(
        "이 기기에서는 온디바이스 음성 인식을 사용할 수 없어요. " +
            "대신 시스템 음성 인식 서비스가 동작하며, 기기 제조사 설정에 따라 " +
            "인터넷을 사용할 수 있어요. ${AgentIdentity.displayName}는 녹음 파일을 " +
            "저장하거나 직접 전송하지 않아요.",
        style = MaterialTheme.typography.bodyMedium,
        color = Clay.Muted,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onContinue, modifier = Modifier.minTouchTarget().testTag("voice-consent-continue")) {
            Text("계속하기")
        }
        TextButton(onClick = onCancel, modifier = Modifier.minTouchTarget().testTag("voice-consent-cancel")) {
            Text("취소")
        }
    }
}

@Composable
private fun CaptureBody(
    state: VoiceCaptureState,
    transcript: String,
    plan: CapturePlan?,
    errorText: String?,
    onMicTap: () -> Unit,
    onSave: () -> Unit,
    onSaveMemo: () -> Unit,
    onDiscard: () -> Unit,
) {
    if (state == VoiceCaptureState.DONE || state == VoiceCaptureState.SAVING ||
        state == VoiceCaptureState.SAVED
    ) {
        ResultCard(transcript, plan, state, errorText)
        Spacer(Modifier.height(14.dp))
    }
    if (state == VoiceCaptureState.ERROR && errorText != null) {
        Text(
            errorText,
            style = MaterialTheme.typography.bodyMedium,
            color = Clay.Error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
    }
    if (state == VoiceCaptureState.LISTENING && transcript.isNotBlank()) {
        Text(
            transcript,
            style = MaterialTheme.typography.bodyMedium,
            color = Clay.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
    }
    MicButton(state = state, onMicTap = onMicTap)
    if (state == VoiceCaptureState.IDLE) {
        Spacer(Modifier.height(10.dp))
        Text(
            "말한 내용은 확인 전까지 어디에도 저장되지 않아요.",
            style = MaterialTheme.typography.bodySmall,
            color = Clay.Muted,
            textAlign = TextAlign.Center,
        )
    }
    if (state == VoiceCaptureState.DONE) {
        Spacer(Modifier.height(12.dp))
        DoneActions(plan, onSave, onSaveMemo, onDiscard)
    }
}

@Composable
private fun ResultCard(
    transcript: String,
    plan: CapturePlan?,
    state: VoiceCaptureState,
    errorText: String?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Clay.Paper),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("voice-result-card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(transcript, style = MaterialTheme.typography.bodyLarge, color = Clay.Ink)
            if (plan != null) {
                if (plan.labels.isNotEmpty() || plan.disposition != null) {
                    // Provenance stays high-contrast — never a pastel hint.
                    Text(
                        (listOfNotNull(plan.disposition?.label) + plan.labels).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Clay.Green,
                    )
                }
                if (plan.reply.message.isNotBlank()) {
                    Text(
                        plan.reply.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (plan.intent == AgentIntent.QUESTION) Clay.Ink else Clay.Muted,
                    )
                }
            } else {
                Text("분류를 확인하지 못했어요.", style = MaterialTheme.typography.bodySmall, color = Clay.Muted)
            }
            when (state) {
                VoiceCaptureState.DONE -> Text(
                    if (plan?.saveable == true) "확인하면 저장해요. 지금은 아직 아무것도 쓰지 않았어요."
                    else "이 내용은 확인 없이 저장하지 않아요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Clay.Muted,
                )
                VoiceCaptureState.SAVING -> Text(
                    "저장하고 있어요…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Clay.Muted,
                )
                VoiceCaptureState.SAVED -> Text(
                    "저장했어요. 부탁 목록에서 확인할 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Clay.Green,
                )
                else -> Unit
            }
            errorText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Clay.Error)
            }
        }
    }
}

@Composable
private fun DoneActions(
    plan: CapturePlan?,
    onSave: () -> Unit,
    onSaveMemo: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (plan != null && plan.saveable && plan.intent != AgentIntent.QUESTION) {
            Button(
                onClick = onSave,
                modifier = Modifier.minTouchTarget().testTag("voice-save"),
            ) { Text("저장하기") }
        } else if (plan == null || plan.disposition == CaptureDisposition.NEEDS_CONFIRM) {
            Button(
                onClick = onSaveMemo,
                modifier = Modifier.minTouchTarget().testTag("voice-save-memo"),
            ) { Text("메모로 저장") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.minTouchTarget().testTag("voice-discard"),
            ) { Text("내려놓기", color = Clay.Muted) }
        }
    }
}

@Composable
private fun MicButton(state: VoiceCaptureState, onMicTap: () -> Unit) {
    val listening = state == VoiceCaptureState.LISTENING
    val enabled = state != VoiceCaptureState.THINKING && state != VoiceCaptureState.SAVING
    val description = when {
        listening -> "듣는 중 · 누르면 마무리"
        else -> "말하기"
    }
    IconButton(
        onClick = onMicTap,
        enabled = enabled,
        modifier = Modifier.size(64.dp)
            .semantics { contentDescription = description }
            .testTag("voice-mic"),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                if (listening) Clay.Coral else Clay.Green, CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(26.dp)) {
                val w = size.width
                val h = size.height
                val ink = Color.White
                drawRoundRect(
                    ink, Offset(w * .36f, h * .10f), Size(w * .28f, h * .44f),
                    CornerRadius(w * .14f),
                )
                drawArc(
                    ink, 10f, 160f, false,
                    Offset(w * .22f, h * .30f), Size(w * .56f, h * .36f),
                    style = Stroke(w * .08f, cap = StrokeCap.Round),
                )
                drawLine(ink, Offset(w * .5f, h * .68f), Offset(w * .5f, h * .84f), w * .08f, StrokeCap.Round)
                drawLine(ink, Offset(w * .32f, h * .88f), Offset(w * .68f, h * .88f), w * .08f, StrokeCap.Round)
            }
        }
    }
}
