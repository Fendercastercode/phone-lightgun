package dev.lightgun.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.ButtonRect
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/** 90s arcade palette. */
object Neon {
    val Night = Color(0xFF0D0221)
    val Deep = Color(0xFF1A0640)
    val Panel = Color(0xFF241056)
    val Magenta = Color(0xFFFF2BD6)
    val Cyan = Color(0xFF00F0FF)
    val Yellow = Color(0xFFFFE600)
    val Lime = Color(0xFF39FF14)
    val Orange = Color(0xFFFF7A00)
    val Red = Color(0xFFFF2244)
    val White = Color(0xFFF4F1FF)
    val Dim = Color(0xFF9C8CD9)
}

val Arcade = FontFamily.Monospace

fun arcade(size: TextUnit = 14.sp, color: Color = Neon.White, glow: Color? = null, weight: FontWeight = FontWeight.Black) =
    TextStyle(fontFamily = Arcade, fontWeight = weight, fontSize = size, color = color, letterSpacing = 1.5.sp,
        shadow = glow?.let { Shadow(it.copy(alpha = 0.85f), Offset(0f, 0f), 12f) })

@Composable
fun RetroTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Neon.Magenta, secondary = Neon.Cyan, tertiary = Neon.Yellow,
            background = Neon.Night, surface = Neon.Panel, onPrimary = Neon.Night, onSurface = Neon.White),
        typography = Typography(
            bodyLarge = t.bodyLarge.copy(fontFamily = Arcade), bodyMedium = t.bodyMedium.copy(fontFamily = Arcade),
            bodySmall = t.bodySmall.copy(fontFamily = Arcade), labelLarge = t.labelLarge.copy(fontFamily = Arcade),
            titleMedium = t.titleMedium.copy(fontFamily = Arcade),
        ),
        content = content,
    )
}

/**
 * Synthwave sunset + perspective grid + attract-mode shooting gallery + CRT scanlines.
 * [animate] runs the grid scroll and the bad guys (off during play: ARCore needs the CPU/GPU).
 */
@Composable
fun RetroBackground(animate: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val arena = remember { AttractMode() }
    val textMeasurer = rememberTextMeasurer()
    var phase by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(2f) }
    if (animate) LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                phase = (phase + dt / 1.6f) % 1f
                arena.update(dt, aspect)
                frame = now
            }
        }
    }
    Box(modifier.fillMaxSize().background(Neon.Night)
        .drawWithContent {
            frame  // read so every animation frame redraws
            val w = size.width; val h = size.height
            aspect = w / h
            // 1987 neon / 1994 digitized street / 1999 polygon warehouse (cycles in attract mode)
            arena.drawBackdrop(this, phase, animate)
            if (animate) arena.draw(this, textMeasurer)
            // scrim: keeps the scene visible but subdued so nothing competes with text
            drawRect(Neon.Night.copy(alpha = 0.45f))
            if (animate) arena.drawOverlay(this)   // the RetroSight logo flies in now and then
            drawContent()
            // scanlines + vignette on top of everything
            var y = 0f
            while (y < h) { drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, y), Size(w, 2f)); y += 5f }
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                Offset(w / 2, h / 2), maxOf(w, h) * 0.75f))
        }, content = content)
}

/** Chrome gradient title with neon outline glow. */
@Composable
fun ChromeTitle(text: String, size: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    Text(text, modifier, style = arcade(size).copy(
        brush = Brush.verticalGradient(listOf(Color.White, Neon.Cyan, Color(0xFF3B5BFF), Neon.Magenta, Color.White)),
        shadow = Shadow(Neon.Magenta, Offset(3f, 3f), 0f), letterSpacing = 3.sp))
}

@Composable
fun Blink(periodMs: Int = 900, content: @Composable (alpha: Float) -> Unit) {
    val tr = rememberInfiniteTransition(label = "blink")
    val a by tr.animateFloat(1f, 0f, infiniteRepeatable(tween(periodMs / 2, easing = { if (it < 0.5f) 0f else 1f }),
        RepeatMode.Reverse), label = "a")
    content(a)
}

/** Beveled panel with a title tab. */
@Composable
fun RetroPanel(title: String?, modifier: Modifier = Modifier, accent: Color = Neon.Cyan,
               content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(top = if (title != null) 10.dp else 0.dp)) {
        Box {
            Column(Modifier.fillMaxWidth().background(Neon.Panel).bevel(accent, 3.dp)
                .padding(start = 12.dp, end = 12.dp, top = if (title != null) 16.dp else 10.dp, bottom = 10.dp),
                content = content)
            if (title != null) Text(" $title ", Modifier.offset(x = 12.dp, y = (-10).dp).background(accent)
                .padding(horizontal = 4.dp), style = arcade(12.sp, Neon.Night))
        }
    }
}

/** Win95-meets-arcade bevel: light top/left, dark bottom/right. */
fun Modifier.bevel(color: Color, width: Dp, pressed: Boolean = false) = drawWithContent {
    drawContent()
    val s = width.toPx()
    val light = if (pressed) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.85f)
    val dark = if (pressed) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.7f)
    drawRect(color, style = Stroke(s))
    drawRect(light, Offset(s, s), Size(size.width - 2 * s, s / 2))
    drawRect(light, Offset(s, s), Size(s / 2, size.height - 2 * s))
    drawRect(dark, Offset(s, size.height - 1.5f * s), Size(size.width - 2 * s, s / 2))
    drawRect(dark, Offset(size.width - 1.5f * s, s), Size(s / 2, size.height - 2 * s))
}

/** Chunky arcade button with drop shadow; sinks when pressed. */
@Composable
fun ArcadeButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Neon.Magenta,
                 enabled: Boolean = true, selected: Boolean = false, small: Boolean = false) {
    var pressed by remember { mutableStateOf(false) }
    // pointerInput keeps its first lambda; read the latest onClick through this instead
    val click by rememberUpdatedState(onClick)
    val c = if (enabled) color else Neon.Dim.copy(alpha = 0.5f)
    val drop = if (pressed) 0.dp else if (small) 3.dp else 5.dp
    val maxDrop = if (small) 3.dp else 5.dp
    Box(modifier.padding(end = maxDrop, bottom = maxDrop)) {
        Box(Modifier.offset(x = maxDrop - drop, y = maxDrop - drop)
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.8f), Offset(drop.toPx(), drop.toPx()), size) }
            .background(if (selected) c else Neon.Deep)
            .bevel(c, if (small) 2.dp else 3.dp, pressed)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val up = waitForUpOrCancellation()
                    pressed = false
                    if (up != null) click()
                }
            }
            .defaultMinSize(minHeight = if (small) 40.dp else 50.dp)  // comfortable touch targets
            .padding(horizontal = if (small) 12.dp else 18.dp, vertical = if (small) 8.dp else 12.dp),
            contentAlignment = Alignment.Center) {
            Text(text.uppercase(), style = arcade(if (small) 11.sp else 14.sp,
                if (selected) Neon.Night else c, glow = if (selected) null else c))
        }
    }
}

/** LED status light. */
@Composable
fun Led(on: Boolean, color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(11.dp).background(if (on) color else color.copy(alpha = 0.18f), CircleShape)
            .border(1.dp, Color.Black, CircleShape))
        Text(" $label", style = arcade(10.sp, if (on) color else Neon.Dim, glow = if (on) color else null))
        Spacer(Modifier.width(10.dp))
    }
}

/** Segmented block meter, 0..1. */
@Composable
fun SegmentBar(fraction: Float, modifier: Modifier = Modifier, segments: Int = 16, color: Color = Neon.Lime) {
    Row(modifier.height(18.dp)) {
        for (i in 0 until segments) {
            val on = i < (fraction * segments).toInt()
            val c = when { i > segments * 0.8 -> Neon.Red; i > segments * 0.55 -> Neon.Yellow; else -> color }
            Box(Modifier.weight(1f).fillMaxSize().padding(1.dp).background(if (on) c else c.copy(alpha = 0.12f)))
        }
    }
}

/**
 * Big press/release arcade zone with hit flash. [interactive] = false drops the press/long-press
 * gesture entirely (used while the zone is being dragged/resized in the Play layout editor, so it
 * doesn't also fire as a button).
 */
@Composable
fun ArcadeZone(label: String, sub: String, color: Color, modifier: Modifier, onPress: (Boolean) -> Unit,
               onLongPress: (() -> Unit)? = null, interactive: Boolean = true, longPressMs: Long = 800) {
    var down by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    Box(modifier.padding(6.dp)
        .background(if (down) color.copy(alpha = 0.6f) else Neon.Night.copy(alpha = 0.85f))
        .bevel(color, 4.dp, down)
        .then(if (!interactive) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down = true; press(true)
                val lp = longPress
                if (lp != null) {
                    val up = withTimeoutOrNull(longPressMs) { waitForUpOrCancellation() }
                    if (up == null) { lp(); waitForUpOrCancellation() }
                } else waitForUpOrCancellation()
                down = false; press(false)
            }
        }), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (label.isNotEmpty()) Text(label, style = arcade(if (label.length > 6) 20.sp else 28.sp,
                if (down) Neon.Night else color, glow = if (down) null else color))
            if (sub.isNotEmpty()) Text(sub, style = arcade(10.sp, if (down) Neon.Night else Neon.Dim))
        }
    }
}

/**
 * Root for a screen whose [MovableZone]s are positioned as fractions of this box, in pixels
 * supplied to [content] once measured.
 */
@Composable
fun EditableLayout(modifier: Modifier = Modifier, content: @Composable BoxScope.(IntSize) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.onSizeChanged { size = it }) {
        if (size != IntSize.Zero) content(size)
    }
}

/**
 * A zone positioned/sized by [rect] (fractions of [container], in px). In [editMode] it can be
 * dragged by its body and resized from the handle in its bottom-right corner; [onRectChange]
 * reports the new rect on every move so the caller can persist it. Outside edit mode this is
 * just a positioned [content] - presses still reach it normally.
 */
@Composable
fun BoxScope.MovableZone(id: String, rect: ButtonRect, container: IntSize, editMode: Boolean,
                          minFraction: Float = 0.08f, onRectChange: (String, ButtonRect) -> Unit,
                          content: @Composable (Modifier) -> Unit) {
    val latest by rememberUpdatedState(rect)
    Box(Modifier
        .offset { IntOffset((rect.x * container.width).roundToInt(), (rect.y * container.height).roundToInt()) }
        .size(with(LocalDensity.current) { (rect.w * container.width).toDp() },
              with(LocalDensity.current) { (rect.h * container.height).toDp() })) {
        content(Modifier.fillMaxSize().then(if (!editMode) Modifier else Modifier
            .border(2.dp, Neon.White.copy(alpha = 0.7f))
            .pointerInput(id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val r = latest
                    val nx = (r.x + drag.x / container.width).coerceIn(0f, 1f - r.w)
                    val ny = (r.y + drag.y / container.height).coerceIn(0f, 1f - r.h)
                    onRectChange(id, r.copy(x = nx, y = ny))
                }
            }))
        if (editMode) {
            Box(Modifier.align(Alignment.BottomEnd).size(30.dp)
                .background(Neon.Yellow.copy(alpha = 0.85f))
                .pointerInput(id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val r = latest
                        val nw = (r.w + drag.x / container.width).coerceIn(minFraction, 1f - r.x)
                        val nh = (r.h + drag.y / container.height).coerceIn(minFraction, 1f - r.y)
                        onRectChange(id, r.copy(w = nw, h = nh))
                    }
                })
        }
    }
}

@Composable
fun RetroSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String = "%.2f",
                color: Color = Neon.Cyan, onChange: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = arcade(11.sp, Neon.White, weight = FontWeight.Bold))
        Text(fmt.format(v), style = arcade(13.sp, Neon.Yellow, glow = Neon.Yellow))
    }
    Slider(v, { v = it }, valueRange = range, onValueChangeFinished = { onChange(v) },
        colors = SliderDefaults.colors(thumbColor = Neon.Yellow, activeTrackColor = color,
            inactiveTrackColor = color.copy(alpha = 0.2f)))
}

@Composable
fun RetroSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Switch(v, { v = it; onChange(it) }, colors = SwitchDefaults.colors(
            checkedThumbColor = Neon.Night, checkedTrackColor = Neon.Lime, uncheckedThumbColor = Neon.Dim,
            uncheckedTrackColor = Neon.Deep, uncheckedBorderColor = Neon.Dim))
        Text("  ${label.uppercase()}", style = arcade(11.sp, if (v) Neon.Lime else Neon.Dim, weight = FontWeight.Bold))
    }
}

/** Pixel crosshair on a mini screen: red target, cyan current aim. */
@Composable
fun TargetScope(x: Double, y: Double, aimX: Float, aimY: Float, modifier: Modifier) {
    Canvas(modifier.background(Color.Black).bevel(Neon.Cyan, 3.dp)) {
        val tx = (x * size.width).toFloat(); val ty = (y * size.height).toFloat()
        val r = size.minDimension * 0.08f
        drawCircle(Neon.Red, r, Offset(tx, ty), style = Stroke(4f))
        drawLine(Neon.Red, Offset(tx - 2 * r, ty), Offset(tx + 2 * r, ty), 3f)
        drawLine(Neon.Red, Offset(tx, ty - 2 * r), Offset(tx, ty + 2 * r), 3f)
        val ax = aimX * size.width; val ay = aimY * size.height
        drawRect(Neon.Cyan, Offset(ax - 6f, ay - 6f), Size(12f, 12f))
    }
}

/**
 * Bottom-of-list hint: a fade plus a blinking "SCROLL FOR MORE" whenever the scrollable content
 * above continues past the bottom edge. Place inside the same Box as the scrolling Column.
 */
@Composable
fun BoxScope.ScrollHint(state: androidx.compose.foundation.ScrollState) {
    if (!state.canScrollForward) return
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(54.dp)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Neon.Night.copy(alpha = 0.95f)))),
        contentAlignment = Alignment.BottomCenter) {
        Blink(1100) { a ->
            Text("▼ SCROLL FOR MORE ▼", Modifier.padding(bottom = 4.dp).alpha(0.45f + 0.55f * a),
                style = arcade(11.sp, Neon.Yellow))
        }
    }
}

/** Red full-width warning strip, e.g. for a new / uncalibrated screen. */
@Composable
fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().background(Color(0xFF4A0010)).bevel(Neon.Red, 2.dp).padding(8.dp)) {
        Blink(1400) { a ->
            Text(text, Modifier.alpha(0.6f + 0.4f * a), style = arcade(12.sp, Neon.White))
        }
    }
}

/** Scrolling column that shows [ScrollHint] when there is more content below. */
@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val state = androidx.compose.foundation.rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(state).padding(bottom = 36.dp), content = content)
        ScrollHint(state)
    }
}
