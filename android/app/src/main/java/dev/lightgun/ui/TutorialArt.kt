package dev.lightgun.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/** Simple animated how-to pictures for the tutorial. */
enum class Art { WELCOME, PAIR, FLOOR, SCAN, HOLD, CORNERS, FIRE, OFFSCREEN, FLICK, RECENTER, ACCURACY, GAME }

@Composable
fun TutorialArt(art: Art, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "art").animateFloat(0f, 1f,
        infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val text = rememberTextMeasurer()
    Canvas(modifier.background(Color(0xFF0A0118)).bevel(Neon.Dim, 2.dp)) {
        when (art) {
            Art.WELCOME -> welcome(text)
            Art.PAIR -> pair(t, text)
            Art.FLOOR -> floor(t, text)
            Art.SCAN -> scan(t, text)
            Art.HOLD -> hold(t, text, targets = 5)
            Art.CORNERS -> hold(t, text, targets = 4)
            Art.FIRE -> fire(t, text, offscreen = false)
            Art.OFFSCREEN -> fire(t, text, offscreen = true)
            Art.FLICK -> flick(t, text)
            Art.RECENTER -> recenter(t, text)
            Art.ACCURACY -> hold(t, text, targets = 0)
            Art.GAME -> game(t, text)
        }
    }
}

// ------------------------------------------------------------------ primitives

private fun DrawScope.label(text: TextMeasurer, s: String, at: Offset, color: Color = Neon.White, size: Int = 12, center: Boolean = true) {
    val l = text.measure(s, TextStyle(fontFamily = Arcade, fontWeight = FontWeight.Bold, fontSize = size.sp, color = color))
    drawText(l, topLeft = if (center) Offset(at.x - l.size.width / 2f, at.y - l.size.height / 2f) else at)
}

/** TV on a stand. Returns its screen rect (left, top, w, h). */
private fun DrawScope.tv(cx: Float, cy: Float, w: Float, glow: Color = Neon.Cyan): FloatArray {
    val h = w * 9f / 16f
    val l = cx - w / 2; val top = cy - h / 2
    drawRect(Color(0xFF1C1C2E), Offset(l - 6, top - 6), Size(w + 12, h + 12))
    drawRect(Color(0xFF07071A), Offset(l, top), Size(w, h))
    drawRect(glow, Offset(l, top), Size(w, h), style = Stroke(2f))
    drawRect(Color(0xFF1C1C2E), Offset(cx - w * 0.06f, top + h + 6), Size(w * 0.12f, h * 0.12f))
    return floatArrayOf(l, top, w, h)
}

/** Phone seen from above/behind, landscape. [angle] degrees. Camera dot on the back-left. */
private fun DrawScope.phone(c: Offset, w: Float, angle: Float = 0f, screen: Color = Neon.Magenta) {
    val h = w * 0.48f
    rotate(angle, c) {
        drawRoundRectCompat(Offset(c.x - w / 2, c.y - h / 2), Size(w, h), Neon.Yellow)
        drawRect(Color(0xFF14082E), Offset(c.x - w / 2 + 6, c.y - h / 2 + 6), Size(w - 12, h - 12))
        drawRect(screen.copy(alpha = 0.5f), Offset(c.x - w / 2 + 10, c.y - h / 2 + 10), Size(w - 20, h - 20))
    }
}

private fun DrawScope.drawRoundRectCompat(o: Offset, s: Size, c: Color) {
    drawRoundRect(c, o, s, androidx.compose.ui.geometry.CornerRadius(10f, 10f))
}

private fun DrawScope.arrow(from: Offset, to: Offset, c: Color, w: Float = 5f) {
    drawLine(c, from, to, w, cap = StrokeCap.Round)
    val d = (to - from); val len = d.getDistance().coerceAtLeast(1f)
    val ux = d.x / len; val uy = d.y / len
    val s = 18f
    drawLine(c, to, Offset(to.x - ux * s - uy * s * 0.6f, to.y - uy * s + ux * s * 0.6f), w, cap = StrokeCap.Round)
    drawLine(c, to, Offset(to.x - ux * s + uy * s * 0.6f, to.y - uy * s - ux * s * 0.6f), w, cap = StrokeCap.Round)
}

private fun DrawScope.dashed(from: Offset, to: Offset, c: Color) =
    drawLine(c, from, to, 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))

private fun DrawScope.crosshair(c: Offset, r: Float, col: Color) {
    drawCircle(col, r, c, style = Stroke(3f))
    drawLine(col, Offset(c.x - r * 1.5f, c.y), Offset(c.x + r * 1.5f, c.y), 3f)
    drawLine(col, Offset(c.x, c.y - r * 1.5f), Offset(c.x, c.y + r * 1.5f), 3f)
}

// ------------------------------------------------------------------ scenes

private fun DrawScope.welcome(text: TextMeasurer) {
    val w = size.width; val h = size.height
    val items = listOf("PC" to Neon.Cyan, "TV / MONITOR" to Neon.Magenta, "SAME WI-FI" to Neon.Lime, "THIS PHONE" to Neon.Yellow)
    items.forEachIndexed { i, (s, c) ->
        val y = h * (0.18f + i * 0.21f)
        drawCircle(c, 14f, Offset(w * 0.18f, y))
        label(text, "✓", Offset(w * 0.18f, y), Neon.Night, 14)
        label(text, s, Offset(w * 0.28f, y - 10f), c, 15, center = false)
    }
}

private fun DrawScope.pair(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    val r = tv(w * 0.36f, h * 0.42f, w * 0.5f)
    // QR code
    val q = r[3] * 0.7f; val qx = r[0] + r[2] / 2 - q / 2; val qy = r[1] + r[3] / 2 - q / 2
    drawRect(Color.White, Offset(qx, qy), Size(q, q))
    val cell = q / 9
    val pat = "101101011010010110111001011010101100101101110100101011011001101010110101"
    for (i in 0 until 9) for (j in 0 until 9) if (pat[(i * 9 + j) % pat.length] == '1')
        drawRect(Color.Black, Offset(qx + i * cell, qy + j * cell), Size(cell, cell))
    // phone moving in to scan
    val px = w * 0.82f - 30f * sin(t * 6.28f)
    phone(Offset(px, h * 0.45f), w * 0.22f, angle = 90f, screen = Neon.Lime)
    dashed(Offset(px - w * 0.06f, h * 0.45f), Offset(r[0] + r[2], h * 0.42f), Neon.Lime)
    label(text, "SCAN THE CODE ON THE PC", Offset(w / 2, h * 0.88f), Neon.Lime, 13)
}

private fun DrawScope.floor(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    // textured floor
    for (i in 0..7) for (j in 0..2) {
        val c = if ((i + j) % 2 == 0) Color(0xFF3A2A1A) else Color(0xFF2A1E12)
        drawRect(c, Offset(i * w / 8, h * 0.72f + j * h * 0.1f), Size(w / 8, h * 0.1f))
    }
    val c = Offset(w * 0.5f, h * 0.3f)
    phone(c, w * 0.26f, angle = 0f)
    // camera cone down to the floor, sweeping
    val sweep = sin(t * 6.28f) * w * 0.08f
    dashed(c, Offset(w * 0.32f + sweep, h * 0.72f), Neon.Lime)
    dashed(c, Offset(w * 0.68f + sweep, h * 0.72f), Neon.Lime)
    label(text, "LOTS OF ANCHOR POINTS = GOOD", Offset(w / 2, h * 0.08f), Neon.Lime, 12)
    label(text, "✖ BLANK WALL / CEILING / DARK ROOM", Offset(w / 2, h * 0.58f), Neon.Orange, 11)
}

private fun DrawScope.scan(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    val r = tv(w * 0.5f, h * 0.26f, w * 0.55f)
    val m = r[3] * 0.22f
    listOf(Offset(r[0] + 6, r[1] + 6), Offset(r[0] + r[2] - m - 6, r[1] + 6), Offset(r[0] + r[2] - m - 6, r[1] + r[3] - m - 6),
        Offset(r[0] + 6, r[1] + r[3] - m - 6), Offset(r[0] + r[2] / 2 - m / 2, r[1] + r[3] / 2 - m / 2)).forEach {
        drawRect(Color.White, it, Size(m, m)); drawRect(Color.Black, Offset(it.x + m * 0.25f, it.y + m * 0.25f), Size(m * 0.5f, m * 0.5f))
    }
    // phone slides left/right while staying pointed at the TV
    val dx = sin(t * 6.28f) * w * 0.22f
    val pc = Offset(w * 0.5f + dx, h * 0.78f)
    phone(pc, w * 0.18f)
    dashed(pc, Offset(r[0] + r[2] / 2, r[1] + r[3] / 2), Neon.Cyan)
    arrow(Offset(w * 0.5f - 40, h * 0.93f), Offset(w * 0.2f, h * 0.93f), Neon.Cyan)
    arrow(Offset(w * 0.5f + 40, h * 0.93f), Offset(w * 0.8f, h * 0.93f), Neon.Cyan)
    label(text, "SLIDE", Offset(w * 0.5f, h * 0.93f), Neon.Cyan, 12)
}

private fun DrawScope.hold(t: Float, text: TextMeasurer, targets: Int) {
    val w = size.width; val h = size.height
    val r = tv(w * 0.5f, h * 0.28f, w * 0.6f)
    val pts = when (targets) {
        5 -> listOf(0.5f to 0.5f, 0.15f to 0.15f, 0.85f to 0.15f, 0.85f to 0.85f, 0.15f to 0.85f)
        4 -> listOf(0.15f to 0.15f, 0.85f to 0.15f, 0.85f to 0.85f, 0.15f to 0.85f)
        else -> listOf(0.3f to 0.4f, 0.7f to 0.25f, 0.55f to 0.75f)
    }
    val active = (t * pts.size).toInt().coerceIn(0, pts.lastIndex)
    pts.forEachIndexed { i, (x, y) ->
        crosshair(Offset(r[0] + r[2] * x, r[1] + r[3] * y), r[3] * 0.08f, if (i == active) Neon.Red else Neon.Dim)
    }
    // player view: phone held landscape, camera toward the TV
    val pc = Offset(w * 0.5f, h * 0.8f)
    phone(pc, w * 0.26f)
    val tgt = pts[active]
    dashed(pc, Offset(r[0] + r[2] * tgt.first, r[1] + r[3] * tgt.second), Neon.Red)
    label(text, "AIM · HOLD STILL · FIRE", Offset(w / 2, h * 0.97f), Neon.Yellow, 12)
}

private fun DrawScope.fire(t: Float, text: TextMeasurer, offscreen: Boolean) {
    val w = size.width; val h = size.height
    val r = tv(w * 0.42f, h * 0.32f, w * 0.5f)
    val pc = Offset(w * 0.5f, h * 0.8f)
    phone(pc, w * 0.24f)
    val aim = if (offscreen) Offset(w * 0.92f, h * 0.2f) else Offset(r[0] + r[2] / 2, r[1] + r[3] / 2)
    val flash = (t * 3f) % 1f < 0.2f
    drawLine(if (flash) Color.White else Neon.Red.copy(alpha = 0.6f), pc, aim, if (flash) 8f else 3f, cap = StrokeCap.Round)
    if (flash) drawCircle(Neon.Yellow, 22f, aim)
    label(text, if (offscreen) "AIM AWAY FROM THE TV + FIRE = RELOAD" else "AIM AT THE TV + FIRE", Offset(w / 2, h * 0.97f),
        if (offscreen) Neon.Cyan else Neon.Red, 12)
}

private fun DrawScope.flick(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    // side view: phone snaps up then back
    val phase = (t * 1.5f) % 1f
    val ang = when {
        phase < 0.15f -> -55f * (phase / 0.15f)
        phase < 0.35f -> -55f + 55f * ((phase - 0.15f) / 0.2f)
        else -> 0f
    }
    val c = Offset(w * 0.5f, h * 0.55f)
    phone(c, w * 0.34f, angle = ang)
    drawArc(Neon.Cyan, 200f, 70f, false, Offset(c.x - w * 0.3f, c.y - w * 0.3f), Size(w * 0.6f, w * 0.6f), style = Stroke(5f))
    label(text, "QUICK SNAP UP + BACK", Offset(w / 2, h * 0.12f), Neon.Cyan, 13)
    label(text, "(LIKE COCKING A SHOTGUN)", Offset(w / 2, h * 0.92f), Neon.Dim, 11)
}

private fun DrawScope.recenter(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    val r = tv(w * 0.5f, h * 0.3f, w * 0.6f)
    val ctr = Offset(r[0] + r[2] / 2, r[1] + r[3] / 2)
    crosshair(ctr, r[3] * 0.12f, Neon.Yellow)
    val pc = Offset(w * 0.5f, h * 0.8f)
    phone(pc, w * 0.3f)
    dashed(pc, ctr, Neon.Yellow)
    // finger holding the middle of the phone screen, with a 1-second progress ring
    val p = (t * 1.2f) % 1f
    drawCircle(Neon.Orange.copy(alpha = 0.8f), 16f, pc)
    drawArc(Neon.Orange, -90f, 360f * p, false, Offset(pc.x - 28, pc.y - 28), Size(56f, 56f), style = Stroke(5f))
    label(text, "AIM AT CENTRE · HOLD MIDDLE OF PHONE 1 SEC", Offset(w / 2, h * 0.97f), Neon.Orange, 11)
}

private fun DrawScope.game(t: Float, text: TextMeasurer) {
    val w = size.width; val h = size.height
    tv(w * 0.5f, h * 0.38f, w * 0.65f, Neon.Lime)
    label(text, "RETROARCH", Offset(w / 2, h * 0.3f), Neon.Lime, 16)
    label(text, if ((t * 2).toInt() % 2 == 0) "PRESS START" else "", Offset(w / 2, h * 0.45f), Neon.Yellow, 13)
    label(text, "HAVE FUN!", Offset(w / 2, h * 0.88f), Neon.Magenta, 16)
}
