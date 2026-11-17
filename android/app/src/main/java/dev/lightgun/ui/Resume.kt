package dev.lightgun.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.AimState
import dev.lightgun.GunController
import dev.lightgun.calib.CalibrationController
import dev.lightgun.calib.CalibrationController.Phase
import kotlinx.coroutines.delay

/** The questions asked after picking a PC, one at a time. */
private enum class RStep { SCREEN, MEASURE, SPOT, PICTURE, NEW_SPOT, LOCK, BARREL, DONE }

/**
 * "Welcome back": after picking a PC, a short check that makes it obvious what's done and what
 * isn't before playing. Same TV? (no -> measure it). Same spot? (yes -> check the saved picture
 * still lines up; no or it doesn't -> save a new one). Then lock on once and do the barrel check
 * once, and into the game. BACK always goes to the previous question.
 */
@Composable
fun ResumeScreen(gun: GunController, aim: AimState, cal: CalibrationController.State,
                 onPlay: () -> Unit, onExit: () -> Unit) {
    var stack by remember { mutableStateOf(listOf(RStep.SCREEN)) }
    val step = stack.last()
    fun go(s: RStep) { stack = stack + s }
    fun back() {
        if (gun.calib.active) gun.cancelCalibration()
        if (stack.size > 1) stack = stack.dropLast(1) else onExit()
    }
    var measuredNow by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(gun.savedLocation()) }
    var live by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        gun.setThumbnailListener { live = it }
        onDispose { gun.setThumbnailListener(null); if (gun.calib.active) gun.cancelCalibration() }
    }
    // the PC knows nothing about this TV yet (or says it's a different one): measuring isn't optional
    val mustMeasure = aim.connected && (gun.newScreen || (gun.link?.lastHelloAck != null && !gun.hostKnowsSize))
    val startSeq = remember(step) { gun.calib.resultSeq }
    fun ok(flow: String) = gun.calib.resultSeq > startSeq && gun.calib.lastResultFlow == flow && gun.calib.lastResultOk

    LaunchedEffect(step) {
        if (step == RStep.LOCK && !(measuredNow && gun.aim.calibrated)) {
            // no need to tap anything: lock-on starts as soon as this question shows
            while (!gun.aim.connected) delay(200)
            delay(600)
            if (!gun.calib.active) gun.startCalibration(if (gun.hostKnowsSize) "Q" else "C")
        }
        while (true) {
            when (step) {
                RStep.MEASURE -> if (ok("A")) { measuredNow = true; gun.haptics.reload(); go(RStep.SPOT); return@LaunchedEffect }
                RStep.LOCK -> if (ok("Q") || ok("C")) { gun.haptics.reload(); go(RStep.BARREL); return@LaunchedEffect }
                RStep.BARREL -> if (ok("B")) { gun.haptics.reload(); go(RStep.DONE); return@LaunchedEffect }
                RStep.DONE -> { delay(1200); gun.lockOnPlay = false; onPlay(); return@LaunchedEffect }
                else -> return@LaunchedEffect
            }
            delay(150)
        }
    }

    val order = listOf(RStep.SCREEN, RStep.SPOT, RStep.LOCK, RStep.BARREL)
    val shownIndex = when (step) { RStep.MEASURE -> 0; RStep.PICTURE, RStep.NEW_SPOT -> 1; RStep.DONE -> 4; else -> order.indexOf(step) }

    Column(Modifier.fillMaxSize().background(Neon.Night).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeButton("◀ BACK", { back() }, small = true, color = Neon.Dim)
            Spacer(Modifier.width(8.dp))
            ChromeTitle("WELCOME BACK", 16.sp)
            Spacer(Modifier.weight(1f))
            // four checks, plain: done = green, now = yellow
            listOf("SCREEN", "SPOT", "LOCK ON", "BARREL").forEachIndexed { i, name ->
                val c = when { i < shownIndex -> Neon.Lime; i == shownIndex -> Neon.Yellow; else -> Neon.Dim }
                Text((if (i < shownIndex) "✓ " else "${i + 1} ") + name,
                    Modifier.padding(horizontal = 6.dp), style = arcade(11.sp, c, weight = FontWeight.Bold))
            }
            Spacer(Modifier.width(8.dp))
            ArcadeButton("SKIP TO GAME", { gun.lockOnPlay = !aim.calibrated; onPlay() }, small = true, color = Neon.Dim)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                when (step) {
                    RStep.SCREEN -> {
                        Question("Same TV as last time?",
                            if (gun.displayLabel.isNotEmpty()) "The PC is showing on: ${gun.displayLabel}" else "")
                        when {
                            !aim.connected -> Note("Connecting to the PC...", Neon.Yellow)
                            mustMeasure -> {
                                Note(if (gun.newScreen) "The PC says this is a different screen, so it needs measuring once."
                                     else "The PC doesn't know this TV's size yet, so it needs measuring once.", Neon.Orange)
                                ArcadeButton("▶ MEASURE IT", { go(RStep.MEASURE) }, color = Neon.Cyan)
                            }
                            else -> Row {
                                ArcadeButton("YES", { go(RStep.SPOT) }, color = Neon.Lime)
                                ArcadeButton("NO, A DIFFERENT ONE", { go(RStep.MEASURE) }, color = Neon.Orange)
                            }
                        }
                        if (!mustMeasure && aim.connected) Note("Yes = no need to measure again.", Neon.Dim)
                    }
                    RStep.MEASURE -> {
                        Question("Measure the TV", "Stand 1-2 m back. Hold the phone sideways with the back camera " +
                            "facing the TV. Tap START, then slowly slide the phone left, right, up and down.")
                        CalStatus(cal, "A")
                        if (!gun.calib.active) ArcadeButton(if (cal.phase == Phase.FAILED && cal.flow == "A") "TRY AGAIN" else "▶ START",
                            { gun.startCalibration("A") }, color = Neon.Cyan, enabled = aim.connected)
                    }
                    RStep.SPOT -> {
                        Question("Standing in the same spot as last time?",
                            saved?.first?.takeIf { it.isNotEmpty() }?.let { "Last time: \"${it.uppercase()}\"" } ?: "")
                        Row {
                            ArcadeButton("YES", { go(if (saved != null) RStep.PICTURE else RStep.NEW_SPOT) }, color = Neon.Lime)
                            ArcadeButton("NO, SOMEWHERE NEW", { go(RStep.NEW_SPOT) }, color = Neon.Orange)
                        }
                    }
                    RStep.PICTURE -> {
                        Question("Does the picture line up?", "Hold the phone how you play. Compare LAST TIME with NOW.")
                        Row {
                            ArcadeButton("✓ LINES UP", { go(RStep.LOCK) }, color = Neon.Lime)
                            ArcadeButton("NO - MAKE A NEW ONE", { go(RStep.NEW_SPOT) }, color = Neon.Orange)
                        }
                    }
                    RStep.NEW_SPOT -> {
                        val locked = aim.tracking && aim.features >= 40
                        Question("Save this spot", "Hold the phone how you'll play. Point it at the room - " +
                            "floor or furniture, not a blank wall.")
                        Note(if (locked) "Camera has a good view." else "Looking for things to track... (${aim.features}/40)",
                            if (locked) Neon.Lime else Neon.Orange)
                        ArcadeButton("✓ SAVE THIS SPOT", {
                            live?.let { gun.saveLocation(saved?.first?.ifEmpty { null } ?: "MY SPOT", it) }
                            saved = gun.savedLocation()
                            go(RStep.LOCK)
                        }, color = Neon.Lime, enabled = locked && live != null)
                    }
                    RStep.LOCK -> {
                        Question("Lock on to the TV", "Point the BACK CAMERA at the TV for a second " +
                            "(phone sideways, its screen facing you).")
                        if (measuredNow && aim.calibrated && !gun.calib.active) {
                            Note("✓ Already locked from measuring.", Neon.Lime)
                            ArcadeButton("NEXT ▶", { go(RStep.BARREL) }, color = Neon.Lime)
                        } else {
                            CalStatus(cal, "Q")
                            if (!gun.calib.active) Row {
                                ArcadeButton("TRY AGAIN", { gun.startCalibration(if (gun.hostKnowsSize) "Q" else "C") }, color = Neon.Lime)
                                ArcadeButton("SHOOT 4 CORNERS", { gun.startCalibration("C") }, small = true, color = Neon.Dim)
                            }
                        }
                    }
                    RStep.BARREL -> {
                        Question("Barrel check", "Hold it like a gun. Tap START, then aim and fire at each of the " +
                            "5 targets on the TV. Hold still when you fire.")
                        CalStatus(cal, "B")
                        if (!gun.calib.active) Row {
                            ArcadeButton(if (cal.phase == Phase.FAILED && cal.flow == "B") "TRY AGAIN" else "▶ START",
                                { gun.startCalibration("B") }, color = Neon.Magenta)
                            if (gun.settings.calibratedBarrel != null)
                                ArcadeButton("SKIP - SAME AS LAST TIME", { go(RStep.DONE) }, small = true, color = Neon.Dim)
                        }
                    }
                    RStep.DONE -> {
                        Question("All set!", "Starting the game...")
                    }
                }
            }
            // right: the picture that goes with the question
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                when (step) {
                    RStep.PICTURE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pic("LAST TIME", saved?.second, Modifier.weight(1f))
                        Pic("NOW", live, Modifier.weight(1f))
                    }
                    RStep.SPOT -> Pic("LAST TIME", saved?.second, Modifier.fillMaxWidth())
                    RStep.NEW_SPOT -> Pic("NOW", live, Modifier.fillMaxWidth())
                    RStep.DONE -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text("✓", style = arcade(90.sp, Neon.Lime, glow = Neon.Lime))
                    }
                    else -> TutorialArt(when (step) {
                        RStep.SCREEN, RStep.MEASURE -> Art.SCAN
                        RStep.LOCK -> Art.CORNERS
                        else -> Art.HOLD
                    }, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Question(title: String, sub: String) {
    Text(title, style = arcade(24.sp, Neon.Yellow, glow = Neon.Yellow))
    if (sub.isNotEmpty()) Text(sub, Modifier.padding(top = 6.dp, bottom = 10.dp),
        style = arcade(14.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 19.sp))
    else Spacer(Modifier.height(10.dp))
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.padding(vertical = 6.dp), style = arcade(12.sp, color, weight = FontWeight.Bold))
}

/** What the running calibration is doing, or why it failed - one line, big enough to read held out. */
@Composable
private fun CalStatus(cal: CalibrationController.State, flow: String) {
    val mine = cal.flow == flow || (flow == "Q" && cal.flow == "C")
    when {
        mine && (cal.phase == Phase.SHOOTING || cal.phase == Phase.SCANNING) -> {
            Note(cal.message.ifEmpty { "Working..." }, Neon.Yellow)
            if (cal.total > 0) SegmentBar(cal.index.toFloat() / cal.total, Modifier.fillMaxWidth().padding(vertical = 4.dp), segments = cal.total)
            else if (cal.progress > 0) SegmentBar(cal.progress.toFloat(), Modifier.fillMaxWidth().padding(vertical = 4.dp), segments = 20)
        }
        mine && cal.phase == Phase.FAILED -> Note("Didn't work: ${cal.message}", Neon.Red)
    }
}

@Composable
private fun Pic(label: String, bmp: Bitmap?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = arcade(12.sp, Neon.Cyan, weight = FontWeight.Bold))
        if (bmp != null) Image(bmp.asImageBitmap(), label, Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxWidth().height(170.dp).background(Color.Black), Alignment.Center) {
            Text("NO PICTURE", style = arcade(11.sp, Neon.Dim))
        }
    }
}
