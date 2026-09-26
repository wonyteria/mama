package kr.mom.probe.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

object Clay {
    val Background = Color(0xFFF6F4F0)
    val Ink = Color(0xFF283D36)
    val Muted = Color(0xFF60756C)
    val Green = Color(0xFF326D5E)
    val Sage = Color(0xFFDDEDE2)
    val Peach = Color(0xFFF7D5C6)
    val Coral = Color(0xFFE96361)
    val CoralDark = Color(0xFFB93F42)
    val Paper = Color(0xFFFFFCF8)
    val Lavender = Color(0xFFE4DFF1)
    val Error = Color(0xFF9A3933)
}

@Composable
fun MomTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val inspectionMode = LocalInspectionMode.current
    if (!inspectionMode && !view.isInEditMode) {
        val window = view.context.findActivity()?.window
        DisposableEffect(window, view) {
            if (window == null) {
                onDispose { }
            } else {
                val controller = WindowCompat.getInsetsController(window, view)
                val previousBars = window.legacyBarStyle()
                val previousLightStatus = controller.isAppearanceLightStatusBars
                val previousLightNavigation = controller.isAppearanceLightNavigationBars
                window.applyClayBarBackground(Clay.Background.toArgb())
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
                onDispose {
                    window.restoreBarStyle(previousBars)
                    controller.isAppearanceLightStatusBars = previousLightStatus
                    controller.isAppearanceLightNavigationBars = previousLightNavigation
                }
            }
        }
    }
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Clay.Green, onPrimary = Color.White,
            background = Clay.Background, onBackground = Clay.Ink,
            surface = Clay.Background, onSurface = Clay.Ink,
            surfaceVariant = Clay.Sage, onSurfaceVariant = Clay.Muted,
            error = Clay.Error, outline = Color(0xFF8E9C93)
        ),
        typography = Typography(
            headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold),
            headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
        ), content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class LegacyBarStyle(
    val statusColor: Int,
    val navigationColor: Int,
    val statusContrast: Boolean,
    val navigationContrast: Boolean,
)

// Status/navigation bar color APIs are deprecated and ignored from API 35, where
// edge-to-edge is enforced; the values are only read and written on older devices.
@Suppress("DEPRECATION")
private fun android.view.Window.legacyBarStyle(): LegacyBarStyle? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) null
    else LegacyBarStyle(statusBarColor, navigationBarColor, isStatusBarContrastEnforced, isNavigationBarContrastEnforced)

@Suppress("DEPRECATION")
private fun android.view.Window.applyClayBarBackground(background: Int) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    statusBarColor = background
    navigationBarColor = background
    isStatusBarContrastEnforced = false
    isNavigationBarContrastEnforced = false
}

@Suppress("DEPRECATION")
private fun android.view.Window.restoreBarStyle(previous: LegacyBarStyle?) {
    if (previous == null || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    statusBarColor = previous.statusColor
    navigationBarColor = previous.navigationColor
    isStatusBarContrastEnforced = previous.statusContrast
    isNavigationBarContrastEnforced = previous.navigationContrast
}

fun Modifier.claySurface(tint: Color = Clay.Background, radius: Dp = 28.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return drawBehind {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = tint.toArgb()
                setShadowLayer(12.dp.toPx(), 4.dp.toPx(), 8.dp.toPx(), Color(0x26929A8D).toArgb())
            }
            val r = radius.toPx()
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
            paint.setShadowLayer(9.dp.toPx(), (-4).dp.toPx(), (-5).dp.toPx(), Color(0xEFFFFFFF).toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
        }
    }.background(Brush.linearGradient(listOf(Color.White.copy(alpha = .70f), tint, tint)), shape)
        .border(.8.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .85f), Color.White.copy(alpha = .12f))), shape)
        .clip(shape)
}

fun Modifier.flatSurface(tint: Color = Clay.Paper, radius: Dp = 22.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return background(tint, shape)
        .border(1.dp, Color(0xFFDCE2DD), shape)
        .clip(shape)
}

@Composable
fun LeafMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(19.dp)) {
        val w = size.width
        val leaf = Path().apply {
            moveTo(w*.18f,w*.79f); cubicTo(w*.05f,w*.21f,w*.64f,w*.13f,w*.91f,w*.14f)
            cubicTo(w*.95f,w*.65f,w*.57f,w*.97f,w*.18f,w*.79f)
        }
        drawPath(leaf, Brush.linearGradient(listOf(Color(0xFFBCD1B4),Color(0xFF78A08A))))
        drawLine(Clay.Green,Offset(w*.14f,w*.96f),Offset(w*.65f,w*.42f),w*.055f,cap=androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Expands small text-level actions to the 48dp minimum accessible touch target. */
fun Modifier.minTouchTarget(): Modifier = sizeIn(minWidth = 48.dp, minHeight = 48.dp)

@Composable
fun ClayCard(modifier: Modifier = Modifier, tint: Color = Clay.Background, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.claySurface(tint).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun AgentCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.claySurface(Clay.Peach, 30.dp).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun BentoCard(modifier: Modifier = Modifier, tint: Color = Clay.Sage, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(tint.copy(alpha = .62f), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
fun AgentButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val factor by animateFloatAsState(if (pressed) .98f else 1f, label = "agent-press")
    Box(
        modifier.fillMaxWidth().heightIn(min = 58.dp).scale(factor)
            .shadow(if (pressed) 2.dp else 9.dp, RoundedCornerShape(22.dp), ambientColor = Clay.Coral, spotColor = Clay.Coral)
            .background(if (enabled) Clay.Coral else Color(0xFFE0D7D2), RoundedCornerShape(22.dp))
            .border(1.dp, Color.White.copy(alpha = .45f), RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Clay.Muted)
    }
}

@Composable
fun ClayButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val factor by animateFloatAsState(if (pressed) .98f else 1f, label = "gentle-press")
    val base = if (primary) Clay.Green else Clay.Sage
    Box(
        modifier.fillMaxWidth().heightIn(min = 56.dp).scale(factor)
            .shadow(if (pressed) 2.dp else 7.dp, RoundedCornerShape(21.dp), ambientColor = base, spotColor = base)
            .background(if (enabled) base else Color(0xFFDBE0D8), RoundedCornerShape(21.dp))
            .border(1.dp, Color.White.copy(alpha = .4f), RoundedCornerShape(21.dp))
            .clip(RoundedCornerShape(21.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) Color(0xFF68736A) else if (primary) Color.White else Clay.Ink)
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text, color = Clay.Muted, style = MaterialTheme.typography.bodySmall, letterSpacing = 1.sp)
}

@Composable
fun StatusPill(text: String, tint: Color = Color.White.copy(alpha = .65f)) {
    Row(Modifier.background(tint, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(Clay.Green, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Clay.Green)
    }
}

@Composable
fun BellMascot(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        scale(size.width / 120f, size.height / 140f, pivot = Offset.Zero) {
            drawOval(Brush.radialGradient(listOf(Color(0x20847869), Color.Transparent), Offset(60f, 122f), 43f), Offset(17f, 114f), Size(86f, 18f))
            drawOval(Color(0xFFC8B9DF), Offset(52f, 7f), Size(18f, 25f))
            drawCircle(Color(0xFFB3977D), 10f, Offset(60f, 112f))
            val bell = Path().apply {
                moveTo(32f, 39f); cubicTo(32f, 8f, 86f, 9f, 90f, 40f)
                lineTo(92f, 81f); cubicTo(92f, 89f, 106f, 95f, 100f, 103f)
                cubicTo(89f, 115f, 28f, 111f, 20f, 99f); cubicTo(15f, 92f, 29f, 86f, 29f, 78f); close()
            }
            drawPath(bell, Brush.linearGradient(listOf(Color(0xFFFFDDC4), Color(0xFFF5B49E), Color(0xFFDD927F)), Offset(20f, 10f), Offset(100f, 115f)))
            val shine = Path().apply { moveTo(39f, 45f); cubicTo(39f, 29f, 47f, 24f, 60f, 23f) }
            drawPath(shine, Color(0xFFFFE9D7), style = Stroke(6f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val hem = Path().apply { moveTo(27f, 96f); quadraticTo(63f, 111f, 95f, 100f) }
            drawPath(hem, Color(0xFFFFDBC6), style = Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawOval(Color(0xFF795C50), Offset(47f, 62f), Size(4.5f, 6f))
            drawOval(Color(0xFF795C50), Offset(70f, 64f), Size(4.5f, 6f))
            val smile = Path().apply { moveTo(56f, 74f); quadraticTo(62f, 80f, 67f, 75f) }
            drawPath(smile, Color(0xFF986D5B), style = Stroke(2.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawCircle(Color(0x55D98F84), 5f, Offset(40f, 74f))
            drawCircle(Color(0x55D98F84), 5f, Offset(81f, 76f))
            drawLine(Color(0xFF9DB497), Offset(106f, 22f), Offset(110f, 13f), 3f)
            drawLine(Color(0xFF9DB497), Offset(111f, 38f), Offset(119f, 34f), 3f)
        }
    }
}

@Composable
fun NavGlyph(kind: String, selected: Boolean, modifier: Modifier = Modifier) {
    val color = if (selected) Clay.Green else Clay.Muted
    Canvas(modifier.size(21.dp)) {
        val w = size.width
        val stroke = Stroke(w * .07f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when (kind) {
            "news", "inbox" -> {
                drawRoundRect(color,Offset(w*.18f,w*.18f),Size(w*.64f,w*.66f),androidx.compose.ui.geometry.CornerRadius(w*.12f),style=stroke)
                drawLine(color,Offset(w*.32f,w*.4f),Offset(w*.68f,w*.4f),w*.06f)
                drawLine(color,Offset(w*.32f,w*.56f),Offset(w*.55f,w*.56f),w*.06f)
            }
            "todo" -> {
                drawRoundRect(color,Offset(w*.18f,w*.14f),Size(w*.64f,w*.72f),androidx.compose.ui.geometry.CornerRadius(w*.12f),style=stroke)
                val check = Path().apply { moveTo(w*.3f,w*.44f); lineTo(w*.44f,w*.56f); lineTo(w*.7f,w*.3f) }
                drawPath(check,color,style=stroke)
                drawLine(color,Offset(w*.3f,w*.72f),Offset(w*.7f,w*.72f),w*.06f)
            }
            "home", "today" -> {
                val path = Path().apply { moveTo(w*.13f,w*.45f); lineTo(w*.5f,w*.13f); lineTo(w*.87f,w*.45f); lineTo(w*.8f,w*.45f); lineTo(w*.8f,w*.88f); lineTo(w*.2f,w*.88f); lineTo(w*.2f,w*.45f) }
                drawPath(path,color,style=stroke)
                drawRect(color,Offset(w*.42f,w*.58f),Size(w*.16f,w*.29f),style=stroke)
            }
            else -> {
                drawCircle(color,w*.29f,style=stroke)
                drawCircle(color,w*.1f,style=stroke)
                for(i in 0..7) {
                    val a = i*Math.PI/4
                    drawLine(color,Offset((w*.5+w*.29*kotlin.math.cos(a)).toFloat(),(w*.5+w*.29*kotlin.math.sin(a)).toFloat()),Offset((w*.5+w*.4*kotlin.math.cos(a)).toFloat(),(w*.5+w*.4*kotlin.math.sin(a)).toFloat()),w*.07f)
                }
            }
        }
    }
}

@Composable
fun ProbeBottomBar(current: String, onSelect: (String) -> Unit) {
    Row(Modifier.navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp)
        .fillMaxWidth().claySurface(radius = 36.dp).padding(7.dp), horizontalArrangement = Arrangement.SpaceAround) {
        listOf("home" to "오늘", "todo" to "할 일", "news" to "소식").forEach { (key, label) ->
            val selected = current == key
            Column(Modifier.weight(1f).background(if (selected) Clay.Sage else Color.Transparent, RoundedCornerShape(26.dp))
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(key) }).padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                NavGlyph(key, selected)
                Text(label, color = if (selected) Clay.Green else Clay.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
