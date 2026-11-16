package dev.lightgun.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Event counters bumped (on the main thread) by GunController; the overlay animates on change. */
class FxBus {
    var shots by mutableIntStateOf(0)
    var reloads by mutableIntStateOf(0)
}

private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

/**
 * Ray-gun blast across the screen for each shot, and a power-up sweep for each reload.
 * Draw-only (no pointer handling), so touches pass straight through to the pads below.
 */
@Composable
fun ShotFxOverlay(fx: FxBus, fromRight: Boolean, modifier: Modifier = Modifier) {
    val shot = remember { Animatable(1f) }
    val reload = remember { Animatable(1f) }
    val text = rememberTextMeasurer()
    LaunchedEffect(fx.shots) {
        if (fx.shots == 0) return@LaunchedEffect
        shot.snapTo(0f)
        shot.animateTo(1f, tween(420, easing = LinearEasing))
    }
    LaunchedEffect(fx.reloads) {
        if (fx.reloads == 0) return@LaunchedEffect
        reload.snapTo(0f)
        reload.animateTo(1f, tween(380, easing = LinearEasing))
    }
    val ps = shot.value
    val pr = reload.value
    if (ps >= 1f && pr >= 1f) return
    Canvas(modifier.fillMaxSize()) {
        if (ps < 1f) drawBlast(ps, fromRight)
        if (pr < 1f) drawReload(pr, fromRight, text)
    }
}

private fun DrawScope.drawBlast(p: Float, fromRight: Boolean) {
    val w = size.width; val h = size.height
    val cy = h * 0.5f
    val x0 = if (fromRight) w * 0.82f else w * 0.18f      // muzzle: over the FIRE pad
    val x1 = if (fromRight) -w * 0.05f else w * 1.05f     // beam exits the far edge
    val travel = easeOut((p / 0.35f).coerceAtMost(1f))
    val head = x0 + (x1 - x0) * travel
    val fade = if (p < 0.45f) 1f else (1f - (p - 0.45f) / 0.55f).coerceAtLeast(0f)
    val thin = 1f - 0.6f * ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)   // beam narrows as it dies

    // screen flash
    if (p < 0.18f) drawRect(Color.White.copy(alpha = 0.32f * (1f - p / 0.18f)))

    // glow layers + white-hot core
    val layers = listOf(Neon.Magenta to 44f, Neon.Cyan to 22f, Color.White to 7f)
    val alphas = listOf(0.22f, 0.55f, 1f)
    layers.forEachIndexed { i, (c, wpx) ->
        drawLine(c.copy(alpha = alphas[i] * fade), Offset(x0, cy), Offset(head, cy), wpx * thin, cap = StrokeCap.Round)
    }

    // two energy strands spiralling around the core
    val len = head - x0
    if (kotlin.math.abs(len) > 4f) {
        for (strand in 0..1) {
            val path = Path()
            val n = 48
            for (k in 0..n) {
                val f = k / n.toFloat()
                val x = x0 + len * f
                val amp = 26f * thin * (0.4f + 0.6f * f)
                val y = cy + amp * sin(f * 9f * PI.toFloat() - p * 40f + strand * PI.toFloat())
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, (if (strand == 0) Neon.Magenta else Neon.Yellow).copy(alpha = 0.85f * fade),
                style = Stroke(width = 4f * thin, cap = StrokeCap.Round))
        }
    }

    // leading spark at the beam head
    if (travel < 1f) {
        drawCircle(Brush.radialGradient(listOf(Color.White, Neon.Cyan.copy(alpha = 0.6f), Color.Transparent),
            Offset(head, cy), 60f), 60f, Offset(head, cy))
    }

    // muzzle flash: star burst
    if (p < 0.28f) {
        val q = p / 0.28f
        val r = 30f + 110f * easeOut(q)
        val a = 1f - q
        for (i in 0 until 10) {
            val ang = i * (2 * PI / 10).toFloat() + 0.3f
            val len2 = if (i % 2 == 0) r else r * 0.55f
            drawLine(Neon.Yellow.copy(alpha = a), Offset(x0, cy),
                Offset(x0 + cos(ang) * len2, cy + sin(ang) * len2), 6f * a + 1f, cap = StrokeCap.Round)
        }
        drawCircle(Color.White.copy(alpha = a), 26f * (1f - q * 0.5f), Offset(x0, cy))
    }

    // impact rings where the beam leaves the screen
    if (p > 0.3f) {
        val q = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        val ex = if (fromRight) 0f else w
        for (k in 0..2) {
            val qq = (q - k * 0.15f).coerceIn(0f, 1f)
            if (qq <= 0f) continue
            drawCircle(Neon.Cyan.copy(alpha = (1f - qq) * 0.8f), 20f + qq * min(w, h) * 0.45f, Offset(ex, cy),
                style = Stroke(6f * (1f - qq) + 1f))
        }
    }
}

private fun DrawScope.drawReload(p: Float, fromRight: Boolean, text: androidx.compose.ui.text.TextMeasurer) {
    val w = size.width; val h = size.height
    // vertical scan line sweeping across, away from the reload pad
    val sweep = easeOut(p)
    val x = if (fromRight) w * sweep else w * (1f - sweep)
    val a = 1f - p
    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Neon.Cyan.copy(alpha = 0.35f * a), Color.Transparent),
        x - 80f, x + 80f), Offset(x - 80f, 0f), Size(160f, h))
    drawLine(Color.White.copy(alpha = a), Offset(x, 0f), Offset(x, h), 3f)
    // power-up rings
    val c = Offset(w / 2, h / 2)
    for (k in 0..1) {
        val qq = (p - k * 0.2f).coerceIn(0f, 1f)
        drawCircle(Neon.Cyan.copy(alpha = (1f - qq) * 0.7f), 30f + qq * h * 0.4f, c, style = Stroke(5f))
    }
    val layout = text.measure("RELOADED!", TextStyle(fontFamily = Arcade, fontWeight = FontWeight.Black,
        fontSize = 34.sp, color = Neon.Cyan.copy(alpha = a), letterSpacing = 3.sp))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f - (p * 30f)))
}
