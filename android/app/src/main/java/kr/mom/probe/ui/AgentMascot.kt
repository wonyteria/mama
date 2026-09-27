package kr.mom.probe.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import kr.mom.probe.agent.AgentIdentity

/**
 * Assistant feature state shown by the rabbit mascot. This is a display
 * contract only — no assistant runtime lives here.
 *
 * [stateLabel] is the user-facing Korean description; tests pin it so the
 * visual state is never communicated by color or shape alone.
 */
enum class AgentMascotState(val stateLabel: String) {
    IDLE("기다리는 중"),
    LISTENING("듣고 있어요"),
    THINKING("정리하고 있어요"),
    DONE("기록했어요"),
    NEW_INFO("새 소식이 있어요"),
}

private val FurCream = Color(0xFFF3EAE0)
private val FurShade = Color(0xFFE5D8C6)
private val InnerEar = Color(0xFFFCE7DA)
private val Face = Color(0xFF57453B)
private val Nose = Color(0xFFC08B76)
private val Sage = Color(0xFF8BAE77)
private val SageDark = Color(0xFF547341)
private val Shadow = Color(0x22847869)

/**
 * Rabbit assistant mascot drawn on Compose Canvas — no image assets, no
 * dependencies. Callers pick a [state] and a [modifier]; the drawing
 * internals stay private so a PNG/Lottie swap later changes nothing here.
 *
 * Semantics contract:
 * - Decorative placements (welcome art, list headers, alarm illustration)
 *   use the default [decorative] = true: the mascot is removed from the
 *   semantics tree so TalkBack does not announce it twice next to copy
 *   that already says the same thing.
 * - Operational surfaces (voice capture, stateful prompts) pass
 *   `decorative = false`: the node then carries the state as
 *   contentDescription/stateDescription AND renders it as visible text.
 *   There is deliberately no way to get an announcing mascot without its
 *   label — the state label is unconditional for non-decorative callers.
 */
@Composable
fun AgentMascot(
    state: AgentMascotState,
    modifier: Modifier = Modifier,
    decorative: Boolean = true,
) {
    Column(
        modifier = if (decorative) {
            modifier.clearAndSetSemantics { }
        } else {
            modifier.semantics {
                contentDescription = "${AgentIdentity.displayName} ${state.stateLabel}"
                stateDescription = state.stateLabel
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().weight(1f, fill = true)) {
            scale(size.width / 120f, size.height / 140f, pivot = Offset.Zero) {
                drawRabbit(state)
            }
        }
        if (!decorative) {
            Text(
                state.stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Clay.Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRabbit(state: AgentMascotState) {
    // Deterministic per-state geometry. No animation, no time sources.
    val leftEarTilt: Float
    val rightEarTilt: Float
    val earLift: Float
    when (state) {
        AgentMascotState.LISTENING -> { leftEarTilt = -5f; rightEarTilt = 5f; earLift = -6f }
        AgentMascotState.THINKING -> { leftEarTilt = -18f; rightEarTilt = 34f; earLift = 0f }
        AgentMascotState.NEW_INFO -> { leftEarTilt = -18f; rightEarTilt = 4f; earLift = -2f }
        AgentMascotState.IDLE, AgentMascotState.DONE -> { leftEarTilt = -16f; rightEarTilt = 16f; earLift = 0f }
    }

    // Ground shadow.
    drawOval(Shadow, Offset(18f, 118f), Size(84f, 14f))

    // Ears behind the head. LISTENING raises them tall; THINKING flops one.
    val earTopY = 8f + earLift
    val earHeight = if (state == AgentMascotState.LISTENING) 50f else 44f
    withTransform({ rotate(leftEarTilt, Offset(44f, earTopY + earHeight)) }) {
        drawOval(FurCream, Offset(34f, earTopY), Size(18f, earHeight))
        drawOval(InnerEar, Offset(38.5f, earTopY + 8f), Size(9f, earHeight - 16f))
    }
    withTransform({ rotate(rightEarTilt, Offset(76f, earTopY + earHeight)) }) {
        drawOval(FurCream, Offset(68f, earTopY), Size(18f, earHeight))
        drawOval(InnerEar, Offset(72.5f, earTopY + 8f), Size(9f, earHeight - 16f))
    }

    // Rounded body/head — calm adult proportions, not chibi.
    drawOval(FurCream, Offset(20f, 44f), Size(80f, 72f))
    drawOval(FurShade, Offset(30f, 100f), Size(60f, 16f))

    // Sage leaf accessory tucked at the side, echoing the widget mascot.
    val leaf = Path().apply {
        moveTo(91f, 58f)
        cubicTo(93f, 47f, 101f, 43f, 107f, 43f)
        cubicTo(106f, 53f, 100f, 59f, 92f, 61f)
        close()
    }
    drawPath(leaf, Sage)
    val vein = Path().apply { moveTo(90f, 64f); lineTo(99f, 49f) }
    drawPath(vein, SageDark, style = Stroke(1.6f, cap = StrokeCap.Round))

    // Restrained expression.
    if (state == AgentMascotState.DONE) {
        // Gentle closed eyes — mirrored shallow arcs.
        val leftClosed = Path().apply { moveTo(45f, 82f); quadraticTo(50f, 78f, 55f, 82f) }
        val rightClosed = Path().apply { moveTo(66f, 82f); quadraticTo(71f, 78f, 76f, 82f) }
        val closed = Stroke(2.4f, cap = StrokeCap.Round)
        drawPath(leftClosed, Face, style = closed)
        drawPath(rightClosed, Face, style = closed)
    } else {
        drawOval(Face, Offset(46f, 76f), Size(5f, 7f))
        drawOval(Face, Offset(69f, 76f), Size(5f, 7f))
    }
    drawOval(Nose, Offset(57f, 88f), Size(6f, 4.5f))
    val mouth = Path().apply {
        moveTo(53f, 95f)
        quadraticTo(60f, 99f, 67f, 95f)
    }
    drawPath(mouth, Face, style = Stroke(2f, cap = StrokeCap.Round))

    // State overlays — shape/marks, never color alone.
    when (state) {
        AgentMascotState.LISTENING -> {
            val arc = Stroke(2.4f, cap = StrokeCap.Round)
            drawArc(Sage, 200f, 100f, false, Offset(16f, 16f), Size(20f, 26f), style = arc)
            drawArc(Sage, 240f, 100f, false, Offset(84f, 16f), Size(20f, 26f), style = arc)
        }
        AgentMascotState.THINKING -> {
            drawCircle(Clay.Muted, 2.4f, Offset(98f, 22f))
            drawCircle(Clay.Muted, 3.2f, Offset(106f, 14f))
            drawCircle(Clay.Muted, 4.2f, Offset(114f, 6f))
        }
        AgentMascotState.DONE -> {
            val check = Path().apply {
                moveTo(90f, 96f)
                lineTo(96f, 102f)
                lineTo(108f, 88f)
            }
            drawPath(check, SageDark, style = Stroke(3f, cap = StrokeCap.Round))
        }
        AgentMascotState.NEW_INFO -> {
            drawLine(SageDark, Offset(60f, 0f), Offset(60f, 9f), 3f, cap = StrokeCap.Round)
            drawCircle(SageDark, 2.2f, Offset(60f, 15f))
        }
        AgentMascotState.IDLE -> Unit
    }
}
