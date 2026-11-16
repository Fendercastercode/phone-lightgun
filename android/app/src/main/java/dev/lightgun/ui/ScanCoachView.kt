package dev.lightgun.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.calib.ScanCoach
import dev.lightgun.calib.ScanCoach.Hint

private fun hintText(h: Hint) = when (h) {
    Hint.FIND_TV -> "POINT THE BACK CAMERA AT THE TV"
    Hint.STEP_BACK -> "STEP BACK - ALL 5 SQUARES MUST FIT"
    Hint.SHOW_ALL -> "AIM AT THE CENTRE SQUARE - SHOW ALL 5"
    Hint.MOVE_CLOSER -> "MOVE A LITTLE CLOSER"
    Hint.TOO_FAST -> "SLOWER!  PAUSE A MOMENT"
    Hint.TILTING -> "SLIDE, DON'T TILT!  MOVE THE WHOLE PHONE"
    Hint.LEFT -> "◀◀  SLIDE LEFT"
    Hint.RIGHT -> "SLIDE RIGHT  ▶▶"
    Hint.UP -> "▲  RAISE THE PHONE"
    Hint.DOWN -> "▼  LOWER THE PHONE"
    Hint.KEEP_GOING -> "GREAT!  KEEP SWEEPING SLOWLY"
}

private fun hintColor(h: Hint) = when (h) {
    Hint.LEFT, Hint.RIGHT, Hint.UP, Hint.DOWN -> Neon.Cyan
    Hint.KEEP_GOING -> Neon.Lime
    Hint.TOO_FAST, Hint.TILTING, Hint.STEP_BACK -> Neon.Orange
    else -> Neon.Yellow
}

/**
 * Flow A coach: big next-move instruction, which markers are visible (mini TV), and a map of
 * where the phone has been with the four target zones it needs to visit.
 */
@Composable
fun ScanCoachView(s: ScanCoach.State, hostProgress: Float, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "coach").animateFloat(0f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(hintText(s.hint), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = arcade(20.sp, hintColor(s.hint), glow = hintColor(s.hint)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- movement map
            Column(Modifier.weight(1.3f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("YOUR MOVES (SEEN FROM BEHIND)", style = arcade(9.sp, Neon.Dim, weight = FontWeight.Bold))
                Canvas(Modifier.weight(1f).aspectRatio(1.4f).background(Color.Black.copy(alpha = 0.6f)).bevel(Neon.Cyan, 2.dp)) {
                    val range = 0.30f  // metres from centre to edge
                    fun px(x: Float) = size.width / 2 + x / range * size.width / 2
                    fun py(y: Float) = size.height / 2 - y / range * size.height / 2
                    val rx = ScanCoach.REACH_X; val ry = ScanCoach.REACH_Y
                    // target zones
                    fun zone(on: Boolean, active: Boolean, l: Float, t: Float, r: Float, b: Float) {
                        val c = if (on) Neon.Lime else if (active) Neon.Cyan else Neon.Dim
                        val a = if (on) 0.35f else if (active) 0.15f + 0.25f * pulse else 0.08f
                        drawRect(c.copy(alpha = a), Offset(px(l), py(t)), Size(px(r) - px(l), py(b) - py(t)))
                        drawRect(c, Offset(px(l), py(t)), Size(px(r) - px(l), py(b) - py(t)), style = Stroke(3f))
                    }
                    zone(s.left, s.hint == Hint.LEFT, -range, ry, -rx, -ry)
                    zone(s.right, s.hint == Hint.RIGHT, rx, ry, range, -ry)
                    zone(s.up, s.hint == Hint.UP, -rx, range, rx, ry)
                    zone(s.down, s.hint == Hint.DOWN, -rx, -ry, rx, -range)
                    // start crosshair
                    drawLine(Neon.Dim, Offset(px(0f), 0f), Offset(px(0f), size.height), 1f)
                    drawLine(Neon.Dim, Offset(0f, py(0f)), Offset(size.width, py(0f)), 1f)
                    // trail + you
                    s.trail.forEachIndexed { i, (x, y) ->
                        drawCircle(Neon.Magenta.copy(alpha = 0.2f + 0.6f * i / s.trail.size.coerceAtLeast(1)), 4f,
                            Offset(px(x.coerceIn(-range, range)), py(y.coerceIn(-range, range))))
                    }
                    val me = Offset(px(s.x.coerceIn(-range, range)), py(s.y.coerceIn(-range, range)))
                    // ghost arrow toward the next zone
                    val target = when (s.hint) {
                        Hint.LEFT -> Offset(px(-(rx + range) / 2), py(0f)); Hint.RIGHT -> Offset(px((rx + range) / 2), py(0f))
                        Hint.UP -> Offset(px(0f), py((ry + range) / 2)); Hint.DOWN -> Offset(px(0f), py(-(ry + range) / 2))
                        else -> null
                    }
                    target?.let { t ->
                        val g = me + (t - me) * pulse
                        drawLine(Neon.Cyan.copy(alpha = 0.6f), me, t, 3f)
                        drawRect(Neon.Cyan.copy(alpha = 1f - pulse), Offset(g.x - 14f, g.y - 9f), Size(28f, 18f), style = Stroke(3f))
                    }
                    // phone icon (landscape)
                    drawRect(Neon.Yellow, Offset(me.x - 16f, me.y - 10f), Size(32f, 20f))
                    drawRect(Neon.Night, Offset(me.x - 12f, me.y - 6f), Size(24f, 12f))
                }
            }
            // ---- markers in view (mini TV)
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SQUARES IN VIEW ${s.markerIds.size}/5", style = arcade(10.sp,
                    if (s.markerIds.size == 5) Neon.Lime else Neon.Yellow, weight = FontWeight.Bold))
                Canvas(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).bevel(Neon.Dim, 2.dp)) {
                    val m = size.height * 0.2f
                    val g = size.height * 0.07f
                    val spots = mapOf(0 to Offset(g, g), 1 to Offset(size.width - g - m, g),
                        2 to Offset(size.width - g - m, size.height - g - m), 3 to Offset(g, size.height - g - m),
                        4 to Offset((size.width - m) / 2, (size.height - m) / 2))
                    spots.forEach { (id, o) ->
                        val on = id in s.markerIds
                        drawRect(if (on) Neon.Lime else Neon.Red.copy(alpha = 0.5f), o, Size(m, m))
                    }
                }
                Text(if (s.moving) "TOO FAST - FRAME SKIPPED" else "STEADY", style = arcade(10.sp,
                    if (s.moving) Neon.Orange else Neon.Lime))
                Text("VIEWS USED ${s.usedFrames}", style = arcade(10.sp, Neon.Dim))
                SegmentBar(hostProgress, Modifier.fillMaxWidth().padding(top = 4.dp))
                Text("PC PROGRESS", style = arcade(9.sp, Neon.Dim))
            }
        }
        Text("STAND 1-2 M BACK · PHONE SIDEWAYS · KEEP AIMING AT THE CENTRE SQUARE WHILE YOU SLIDE",
            textAlign = TextAlign.Center, style = arcade(9.sp, Neon.White, weight = FontWeight.Bold))
    }
}
