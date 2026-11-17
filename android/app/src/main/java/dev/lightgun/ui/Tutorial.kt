package dev.lightgun.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.AimState
import dev.lightgun.GunController
import dev.lightgun.calib.CalibrationController
import dev.lightgun.calib.CalibrationController.Phase
import dev.lightgun.net.Protocol
import kotlinx.coroutines.delay

/** Snapshot of counters when a stage starts, so "done" means "done during this stage". */
private data class Baseline(val shots: Int, val offscreen: Int, val flicks: Int, val recenters: Int, val resultSeq: Int)

/** What the right-hand side shows while the stage runs. */
private enum class Pane { ART, FIRE, COACH, CAMERA, HOLD_PAD }

/** How important a step is for being able to play. */
private enum class Need { REQUIRED, RECOMMENDED, OPTIONAL }

private enum class Status { DONE, CURRENT, NEEDED, OPTIONAL }

private class Stage(
    val id: String,
    val short: String,                // name used in the "still need" summary
    val title: String,
    val color: Color,
    val goal: String,
    val steps: List<String>,
    val art: Art,
    val need: Need = Need.OPTIONAL,
    val pane: Pane = Pane.ART,
    val success: String,
    val stuck: List<String> = emptyList(),
    val waiting: String,
    val action: Pair<String, (GunController) -> Unit>? = null,
    val skippable: Boolean = true,
    /** Is this already true right now (independent of the tutorial)? null = only "done once". */
    val live: ((GunController) -> Boolean)? = null,
    /** Completion while the stage is open. Polled every 100 ms. */
    val done: (GunController, Baseline) -> Boolean,
)

private fun result(g: GunController, b: Baseline, flow: String) =
    g.calib.resultSeq > b.resultSeq && g.calib.lastResultFlow == flow && g.calib.lastResultOk

private fun stages(onScanQr: () -> Unit): List<Stage> = listOf(
    Stage("welcome", "Welcome", "WELCOME!", Neon.Yellow,
        "Your phone is about to become a light gun.",
        listOf("This takes about 5 minutes, one step at a time.",
            "Most steps finish by themselves as soon as you've done them.",
            "The boxes along the top show what's done (green \u2713) and what you still need (red)."),
        Art.WELCOME, success = "You tap START.", waiting = "Tap START when you're ready",
        action = "\u25B6 START" to { _ -> }, skippable = false, done = { _, _ -> false }),

    Stage("connect", "Connect to PC", "CONNECT TO YOUR PC", Neon.Cyan,
        "The phone needs to talk to a small program on your PC.",
        listOf("On the PC, open the RetroSight folder and double-click start-host.bat.",
            "In the window that opens, click SHOW PAIRING QR. A big square code appears on your TV.",
            "Tap SCAN QR and point the phone's camera at that code."),
        Art.PAIR, need = Need.REQUIRED, success = "This step turns green and the PC window says \"Paired\".",
        stuck = listOf("Windows asked about the firewall? Choose ALLOW (private networks).",
            "Phone and PC must be on the same Wi-Fi network.",
            "No start-host.bat? In the RetroSight folder, in a terminal run:  cd host  then  .venv\\Scripts\\python -m lightgun_host"),
        waiting = "Waiting for the PC to answer...", action = "\u25B6 SCAN QR" to { _ -> onScanQr() }, skippable = false,
        live = { g -> g.link?.connected == true }, done = { g, _ -> g.link?.connected == true }),

    Stage("camera", "Camera check", "CAMERA CHECK", Neon.Lime,
        "It tracks itself using the room - not the TV. Point the back camera at the floor or furniture.",
        listOf("Hold the phone how you'll actually play.",
            "LOCKED ON? Tap ANCHOR.",
            "Like the picture? Tap YES, TRACK THIS."),
        Art.FLOOR, need = Need.REQUIRED, pane = Pane.CAMERA,
        success = "You've anchored and confirmed the picture of what it's tracking.",
        stuck = listOf("Turn on a light - in a dark room the camera can't see any anchor points.",
            "Plain white walls and ceilings have no anchor points. Aim at the floor, a rug, a shelf or furniture.",
            "Wipe the camera lens.",
            "Still nothing to lock onto? Print a marker and stick it on the wall near the TV - a " +
                "fool-proof fallback anchor for rooms with nothing else to track."),
        waiting = "Finding anchor points in the room...", skippable = false,
        // No `live` check here on purpose: the point of this step is to show the player what's
        // being tracked *right now* and let them confirm/save it, so it shouldn't be able to
        // flag itself ALREADY DONE and offer an instant skip just because tracking happened to
        // already be live from whatever the camera was pointed at before this step opened.
        done = { g, _ -> g.aim.tracking && !g.aim.gyroOnly && g.aim.features >= 40 }),

    Stage("measure", "Measure your TV", "MEASURE YOUR TV", Neon.Cyan,
        "The phone looks at the TV once to learn where it is and how big it is. Needed again for a new screen.",
        listOf("Stand 1-2 m from the TV. Hold the phone sideways in your hand, back camera facing the TV.",
            "Tap START SCAN. Five black-and-white squares appear on the TV.",
            "Follow the arrows on the right: slide the whole phone left, right, up and down, slowly, keeping it pointed at the middle square."),
        Art.SCAN, need = Need.REQUIRED, pane = Pane.COACH, success = "\"Screen measured\" appears and this step turns green.",
        stuck = listOf("Slide - don't just tilt the phone. Moving it sideways is what counts.",
            "All 5 squares must be in view: step back if one is cut off.",
            "Move slowly and pause at each spot. Glare on the TV can hide the squares."),
        waiting = "Scanning the TV...", action = "\u25B6 START SCAN" to { g -> g.startCalibration("A") },
        live = { g -> g.hostKnowsSize && !g.newScreen }, done = { g, b -> result(g, b, "A") }),

    Stage("barrel", "Hold it like a gun", "HOLD IT LIKE A GUN", Neon.Magenta,
        "The app learns how YOU aim by having you shoot 5 targets. Do it again if you change how you hold the phone.",
        listOf("Hold the phone the way you'll play. No grip? Sideways, back camera pointing at the TV.",
            "Tap START. A red crosshair appears on the TV.",
            "Aim, hold still, fire (Volume Down or FIRE). Repeat for all 5."),
        Art.HOLD, need = Need.RECOMMENDED, pane = Pane.FIRE, success = "\"Barrel calibrated\" appears and this step turns green.",
        stuck = listOf("A buzz means you moved while firing - hold still and fire again.",
            "Hold the phone exactly the same way for every shot.",
            "\"Scan the TV first\"? Go BACK one step and measure the TV again."),
        waiting = "Shoot the targets on the TV", action = "\u25B6 START" to { g -> g.startCalibration("B") },
        live = { g -> g.settings.calibratedBarrel != null }, done = { g, b -> result(g, b, "B") }),

    Stage("lockon", "Lock on", "LOCK ON", Neon.Lime,
        "Lock-on is the only thing you do each time you play - and PLAY does it for you.",
        listOf("Hold the phone the same way as the last step.",
            "Tap START. Black-and-white squares flash on the TV.",
            "Point the BACK CAMERA at the TV for a second until it says LOCKED ON. Hold the phone sideways with its screen facing you, so the camera on the back looks at the TV.",
            "Phone slept or you put it down? PLAY locks on again by itself - just point at the TV."),
        Art.CORNERS, need = Need.REQUIRED, pane = Pane.FIRE, success = "\"Locked on\" appears and this step turns green.",
        stuck = listOf("Stand where the camera can see the whole TV, 1-3 m away.",
            "Glare on the TV can hide the squares - dim the lights behind you.",
            "Still stuck? On the CALIBRATE tab use LOCK ON BY SHOOTING 4 CORNERS."),
        waiting = "Point the back camera at the TV", action = "\u25B6 START" to { g -> g.startCalibration("Q") },
        live = { g -> g.aim.calibrated && !g.newScreen }, done = { g, b -> result(g, b, "Q") || result(g, b, "C") }),

    Stage("fire", "Fire", "FIRE!", Neon.Red,
        "Try a real shot. The mouse pointer on your PC now follows the gun.",
        listOf("Aim at the TV and pull the trigger (Volume Down or the FIRE pad)."),
        Art.FIRE, pane = Pane.FIRE, success = "You feel a click and a kick, and the screen flashes.",
        stuck = listOf("Pointer not moving? Make sure the right monitor is picked in the PC window."),
        waiting = "Fire at the TV", done = { g, b -> g.aim.shots > b.shots }),

    Stage("offscreen", "Off-screen reload", "RELOAD: SHOOT OFF-SCREEN", Neon.Cyan,
        "Many light-gun games reload when you shoot away from the screen.",
        listOf("Point the gun away from the TV - at a wall or the floor - and fire."),
        Art.OFFSCREEN, pane = Pane.FIRE, success = "A single click and \"RELOADED!\"",
        stuck = listOf("Make sure you're pointing clearly outside the TV's edge."),
        waiting = "Fire away from the TV", done = { g, b -> g.aim.offscreenReloads > b.offscreen }),

    Stage("flick", "Flick reload", "RELOAD: FLICK", Neon.Cyan,
        "You can also reload with a quick flick of the wrist.",
        listOf("Snap the phone up and back, fast - like cocking a shotgun."),
        Art.FLICK, success = "A single click and \"RELOADED!\"",
        stuck = listOf("It needs to be quick and sharp. Normal aiming won't trigger it.",
            "Too hard or too easy? Change \"Flick threshold\" in OPTIONS later."),
        waiting = "Flick the gun", done = { g, b -> g.aim.flicks > b.flicks }),

    Stage("recenter", "Recenter", "RECENTER", Neon.Yellow,
        "If the aim ever drifts a little, this fixes it in one second.",
        listOf("Aim at the exact centre of the TV.", "Press and hold the HOLD pad on the right for one second."),
        Art.RECENTER, pane = Pane.HOLD_PAD, success = "A click and \"Recentered\".",
        stuck = listOf("\"Recenter refused\" means you weren't aiming near the centre - try again."),
        waiting = "Aim at the centre and hold the pad", done = { g, b -> g.aim.recenters > b.recenters }),

    Stage("accuracy", "Accuracy test", "ACCURACY TEST (OPTIONAL)", Neon.Magenta,
        "See how accurate your setup is.",
        listOf("Tap START TEST. 30 targets appear on the TV, one at a time.", "Shoot each one. The PC shows your score at the end."),
        Art.ACCURACY, pane = Pane.FIRE, success = "The PC shows your score. Under 1% is great.",
        waiting = "Shoot the targets on the TV", action = "▶ START TEST" to { g -> g.startCalibration("T") },
        done = { g, b -> result(g, b, "T") }),

    Stage("game", "Set up a game", "READY TO PLAY!", Neon.Lime,
        "Last step: start a game on the PC. The PC app spots the emulator and game by itself.",
        listOf("On the PC, open RetroArch (or MAME, etc.) and load a light-gun game, full screen.",
            "The PC window shows if the emulator isn't seeing the gun - click FIX there (or tap FIX in PLAY).",
            "RetroArch: after a fix, load the game again. Then tap PLAY!"),
        Art.GAME, success = "Shots land where you aim in the game.",
        stuck = listOf("Switched games? Just load the new one - the PC follows it (RetroArch needs Network Commands on; FIX turns it on).",
            "Other emulators: see host/presets.md in the RetroSight folder on your PC."),
        waiting = "Tap PLAY!", action = "\u25B6 PLAY!" to { _ -> }, skippable = false, done = { _, _ -> false }),
)

private fun statusOf(st: Stage, i: Int, current: Int, g: GunController, doneOnce: Set<String>): Status {
    val done = st.live?.invoke(g) ?: (st.id in doneOnce)
    return when {
        done -> Status.DONE
        i == current -> Status.CURRENT
        st.need == Need.OPTIONAL -> Status.OPTIONAL
        else -> Status.NEEDED
    }
}

@Composable
fun TutorialScreen(gun: GunController, aim: AimState, cal: CalibrationController.State,
                   onScanQr: () -> Unit, onFinish: () -> Unit) {
    val list = remember(onScanQr) { stages(onScanQr) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var cleared by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    // Keyed on hostId: switching to a different pc (or forgetting one and re-adding it, which
    // lands on a brand new SavedHost row) re-reads that pc's own progress instead of whatever
    // was marked done for some other pc - or for NO pc, before one was ever selected. The
    // phone-wide set is used ONLY while no pc is selected yet (just "welcome", in practice):
    // once a pc exists, its own steps are the whole story, never unioned with the phone-wide
    // set - that set accumulates across every pc ever paired with this phone (including ones
    // from before this per-pc tracking existed), so unioning it in here would make a brand new
    // pc look like it already finished steps it never actually ran for THIS pc.
    var doneOnce by remember(gun.hostId) { mutableStateOf(if (gun.hostId == null) gun.settings.tutorialSteps else gun.hostTutorialSteps()) }
    var coach by remember { mutableStateOf(gun.coach.state) }
    LaunchedEffect(Unit) { while (true) { coach = gun.coach.state; delay(80) } }
    var camThumb by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        gun.setThumbnailListener { camThumb = it }
        onDispose { gun.setThumbnailListener(null) }
    }
    val stage = list[index.coerceIn(0, list.lastIndex)]
    val statuses = list.mapIndexed { i, st -> statusOf(st, i, index, gun, doneOnce) }
    val alreadyDone = statuses[index] == Status.DONE && !cleared
    val missing = list.filterIndexed { i, st -> st.need == Need.REQUIRED && statuses[i] != Status.DONE }

    fun finish() { gun.markHostTutorialDone(true); onFinish() }
    fun go(i: Int) { cleared = false; showHelp = false; index = i.coerceIn(0, list.lastIndex) }
    fun next() = if (index < list.lastIndex) go(index + 1) else finish()
    fun markDone(id: String) { doneOnce = doneOnce + id; gun.addHostTutorialStep(id) }
    // New PC, new room, or just want a clean run: forget every step was ever done instead of
    // the player having to remove their PC as a workaround to force a fresh walkthrough.
    fun restart() { gun.resetTutorial(); doneOnce = emptySet(); go(0) }

    // Camera check never auto-advances: two explicit taps, not a timer. ANCHOR captures the
    // picture (anchorCaptured); only after actually looking at it does the player tap YES, TRACK
    // THIS (cameraConfirmed) to move on - or RETRY to re-lock and look again. Both reset whenever
    // the stage is (re)entered.
    var anchorCaptured by remember(index) { mutableStateOf(false) }
    var cameraConfirmed by remember(index) { mutableStateOf(false) }
    var capturedThumb by remember(index) { mutableStateOf<Bitmap?>(null) }

    // Poll the completion condition; on success flash STAGE CLEAR, click, advance.
    LaunchedEffect(index) {
        val a = gun.aim
        val base = Baseline(a.shots, a.offscreenReloads, a.flicks, a.recenters, gun.calib.resultSeq)
        cleared = false
        while (true) {
            // Camera check completes on the explicit YES, TRACK THIS tap alone - tracking only
            // has to be good enough to let that tap happen (gates the ANCHOR button itself), not
            // still be live at the exact instant this loop polls afterward. Every other stage
            // still completes from its own live condition.
            val ready = if (stage.pane == Pane.CAMERA) cameraConfirmed else stage.done(gun, base)
            if (ready) break
            delay(100)
        }
        markDone(stage.id)
        cleared = true
        gun.haptics.reload()
        delay(1500)
        next()
    }

    Column(Modifier.fillMaxSize().background(Neon.Night)) {
        Header(list, statuses, index, missing.map { it.short }, onJump = ::go, onExit = ::finish, onRestart = ::restart)
        if (gun.newScreen) WarningBanner("NEW SCREEN (${gun.displayLabel}) - MEASURE YOUR TV AND LOCK ON AGAIN",
            Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------------- left: what to do (scrolls) + pinned buttons
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 40.dp)) {
                        if (alreadyDone) Text("\u2713 ALREADY DONE - redo it if you like, or tap NEXT",
                            Modifier.fillMaxWidth().background(Color(0xFF0E3A12)).padding(8.dp), style = arcade(12.sp, Neon.Lime))
                        RetroPanel(stage.title, accent = if (alreadyDone) Neon.Lime else stage.color) {
                            Text(stage.goal, style = body(15.sp, stage.color))
                            Spacer(Modifier.height(6.dp))
                            stage.steps.forEachIndexed { i, line ->
                                Row(Modifier.padding(vertical = 3.dp)) {
                                    Box(Modifier.size(24.dp).background(stage.color, CircleShape), Alignment.Center) {
                                        Text("${i + 1}", style = arcade(12.sp, Neon.Night))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(line, style = body(14.sp, Neon.White))
                                }
                            }
                        }
                        if (showHelp) RetroPanel("IF IT'S NOT WORKING", accent = Neon.Orange) {
                            stage.stuck.forEach { Text("\u2022 $it", Modifier.padding(vertical = 2.dp), style = body(13.sp, Neon.White)) }
                        }
                        if (stage.id == "measure" && gun.hostKnowsSize && !gun.newScreen)
                            Text("The PC already knows this TV's size - you can skip this step.",
                                Modifier.padding(top = 4.dp), style = body(13.sp, Neon.Lime))
                    }
                    ScrollHint(scroll)
                }
                // pinned: always visible, never scrolled away
                Row(Modifier.fillMaxWidth().background(Neon.Night).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    stage.action?.let { (label, fn) ->
                        ArcadeButton(label, {
                            fn(gun)
                            if (index == 0) { markDone(stage.id); next() }
                            if (index == list.lastIndex) { markDone(stage.id); finish() }
                        }, color = stage.color)
                    }
                    if (index == 0)
                        ArcadeButton("JOIN A FRIEND'S GAME", { onScanQr() }, small = true, color = Neon.Cyan)
                    if (stage.stuck.isNotEmpty())
                        ArcadeButton(if (showHelp) "HIDE HELP" else "STUCK?", { showHelp = !showHelp }, small = true, color = Neon.Orange)
                    if (index > 0) ArcadeButton("\u25C0 BACK", { go(index - 1) }, small = true, color = Neon.Dim)
                    if (alreadyDone || stage.skippable)
                        ArcadeButton(if (alreadyDone) "NEXT \u25B6" else "SKIP", { next() }, small = true,
                            color = if (alreadyDone) Neon.Lime else Neon.Dim)
                }
            }
            // ---------------- right: picture / live view + status
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        stage.pane == Pane.COACH && coach.active ->
                            ScanCoachView(coach, cal.progress.toFloat(), Modifier.fillMaxSize().background(Color(0xFF0A0118)).padding(6.dp))
                        stage.pane == Pane.CAMERA -> TutorialArt(stage.art, Modifier.fillMaxSize())   // the real thing is the big popup below
                        stage.pane == Pane.FIRE -> Column(Modifier.fillMaxSize()) {
                            TutorialArt(stage.art, Modifier.weight(1f).fillMaxWidth())
                            ArcadeZone("FIRE!", "OR PRESS VOLUME DOWN", Neon.Red, Modifier.weight(1f).fillMaxWidth(),
                                { gun.setButton(Protocol.BTN_TRIGGER, it) })
                        }
                        stage.pane == Pane.HOLD_PAD -> Column(Modifier.fillMaxSize()) {
                            TutorialArt(stage.art, Modifier.weight(1f).fillMaxWidth())
                            ArcadeZone("HOLD", "1 SECOND", Neon.Yellow, Modifier.weight(1f).fillMaxWidth(), {},
                                onLongPress = { gun.recenter() })
                        }
                        else -> TutorialArt(stage.art, Modifier.fillMaxSize())
                    }
                    if (cleared) Box(Modifier.fillMaxSize().background(Neon.Night.copy(alpha = 0.95f)), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ChromeTitle("STAGE", 34.sp)
                            ChromeTitle("CLEAR!", 40.sp)
                        }
                    }
                }
                StatusBar(stage, cal, cleared, alreadyDone, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        // The real camera-check UI: a big popup over both columns, not squeezed into the small
        // right-hand pane - the live picture needs to actually be readable, not a thumbnail.
        if (stage.pane == Pane.CAMERA) {
            Box(Modifier.fillMaxSize().background(Neon.Night.copy(alpha = 0.92f)), Alignment.Center) {
                CameraMeter(gun, aim, camThumb, capturedThumb, anchorCaptured, cameraConfirmed,
                    onAnchor = { capturedThumb = camThumb; anchorCaptured = true }, onConfirm = { cameraConfirmed = true },
                    onRetry = { anchorCaptured = false },
                    Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f))
            }
        }
        }
    }
}

/** Plain readable body text: sentence case, bold monospace, generous line height, no glow. */
private fun body(size: TextUnit, color: Color) =
    arcade(size, color, weight = FontWeight.Bold).copy(letterSpacing = 0.3.sp, lineHeight = size * 1.35f)

@Composable
private fun Header(list: List<Stage>, statuses: List<Status>, index: Int, missing: List<String>,
                   onJump: (Int) -> Unit, onExit: () -> Unit, onRestart: () -> Unit) {
    var confirmRestart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF14062E)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeTitle("HOW TO PLAY", 16.sp)
            Spacer(Modifier.width(10.dp))
            // one chip per step: tap to jump
            list.forEachIndexed { i, st ->
                val s = statuses[i]
                val c = when (s) { Status.DONE -> Neon.Lime; Status.CURRENT -> Neon.Yellow
                    Status.NEEDED -> if (st.need == Need.RECOMMENDED) Neon.Orange else Neon.Red; Status.OPTIONAL -> Neon.Dim }
                val selected = i == index
                Box(Modifier.padding(horizontal = 2.dp).size(if (selected) 36.dp else 30.dp)
                    .background(if (s == Status.DONE) c else Neon.Night)
                    .bevel(c, if (selected) 3.dp else 2.dp)
                    .clickable { onJump(i) }, Alignment.Center) {
                    Text(if (s == Status.DONE) "\u2713" else "${i + 1}",
                        style = arcade(if (selected) 14.sp else 12.sp, if (s == Status.DONE) Neon.Night else c))
                }
            }
            Spacer(Modifier.weight(1f))
            ArcadeButton(if (confirmRestart) "TAP AGAIN TO RESTART" else "RESTART TUTORIAL",
                { if (confirmRestart) { confirmRestart = false; onRestart() } else confirmRestart = true },
                small = true, color = if (confirmRestart) Neon.Orange else Neon.Dim)
            ArcadeButton("EXIT", onExit, small = true, color = Neon.Dim)
        }
        Text(if (missing.isEmpty()) "\u2713 READY TO PLAY! The rest is practice - tap any box to try it."
             else "TO PLAY YOU STILL NEED: " + missing.joinToString("  \u2022  "),
            style = arcade(11.sp, if (missing.isEmpty()) Neon.Lime else Neon.Yellow, weight = FontWeight.Bold))
        Row {
            Legend(Neon.Lime, "done"); Legend(Neon.Red, "needed to play"); Legend(Neon.Orange, "recommended")
            Legend(Neon.Dim, "optional practice")
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Box(Modifier.size(9.dp).background(c))
        Text(" $label", style = arcade(9.sp, Neon.Dim, weight = FontWeight.Bold))
    }
}

@Composable
private fun StatusBar(stage: Stage, cal: CalibrationController.State, cleared: Boolean, alreadyDone: Boolean, modifier: Modifier) {
    val failed = cal.phase == Phase.FAILED
    val calMsg = if (cal.phase == Phase.SHOOTING || cal.phase == Phase.SCANNING || failed) cal.message else ""
    val (color, label, line) = when {
        cleared -> Triple(Neon.Lime, "DONE!", "Moving to the next step...")
        failed -> Triple(Neon.Red, "TRY AGAIN", calMsg)
        alreadyDone && calMsg.isEmpty() -> Triple(Neon.Lime, "ALREADY DONE", "Tap NEXT, or redo this step")
        else -> Triple(Neon.Yellow, "WAITING FOR", calMsg.ifEmpty { stage.waiting })
    }
    Column(modifier.background(Color(0xFF14062E)).bevel(color, 2.dp).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Blink(1000) { a ->
                Box(Modifier.size(16.dp).alpha(if (cleared || alreadyDone) 1f else 0.4f + 0.6f * a).background(color, CircleShape))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = arcade(12.sp, color))
                Text(line, style = body(14.sp, Neon.White))
            }
        }
        Text("WORKED WHEN: ${stage.success}", Modifier.padding(top = 4.dp), style = body(12.sp, Neon.Lime))
    }
}

/**
 * Big and simple on purpose: this is read at arm's length with the phone held sideways like a
 * gun, not studied up close. The live camera picture is always on screen (not hidden while
 * searching - seeing *why* it's not tracking yet, e.g. a dim room, matters). Two explicit taps,
 * never a timer: ANCHOR freezes the current picture to review; YES, TRACK THIS is what the
 * caller polls to actually advance the stage. RETRY un-freezes it to re-lock and look again.
 */
@Composable
private fun CameraMeter(gun: GunController, aim: AimState, thumb: Bitmap?, capturedThumb: Bitmap?,
                        anchorCaptured: Boolean, confirmed: Boolean, onAnchor: () -> Unit,
                        onConfirm: () -> Unit, onRetry: () -> Unit, modifier: Modifier) {
    val locked = aim.tracking && aim.features >= 40
    var showSave by remember { mutableStateOf(false) }
    val saved = remember(showSave) { gun.savedLocation() }   // re-read right after a save closes the popup
    val shownPic = if (anchorCaptured) capturedThumb else thumb
    Column(modifier.background(Color(0xFF0A0118)).bevel(Neon.Dim, 2.dp).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (anchorCaptured) "IS THIS WHAT YOU WANT TO TRACK?" else "HOLD THE PHONE\nHOW YOU WANT TO PLAY",
            textAlign = TextAlign.Center, style = arcade(20.sp, Neon.Yellow, glow = Neon.Yellow).copy(lineHeight = 24.sp))
        if (anchorCaptured) Text("This keeps your aim locked in place while you play.",
            textAlign = TextAlign.Center, style = body(13.sp, Neon.White))
        // The actual live picture (or, once anchored, the frozen shot) - not an abstract meter.
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), Alignment.Center) {
            if (shownPic != null) Image(shownPic.asImageBitmap(), "what the camera is tracking",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else TutorialArt(Art.FLOOR, Modifier.fillMaxSize())
        }
        when {
            !anchorCaptured -> {
                Text(if (locked) "LOCKED ON" else if (aim.tracking) "SEARCHING..." else "NOT TRACKING",
                    style = arcade(20.sp, if (locked) Neon.Lime else Neon.Orange, glow = if (locked) Neon.Lime else null))
                // A clear, specific reason it's NOT good yet - not just a silent/stuck meter.
                if (!locked) Text(
                    if (!aim.tracking) "CAMERA LOST THE ROOM" + (aim.failure?.let { " (${it.replace('_', ' ').lowercase()})" } ?: "")
                    else "ONLY ${aim.features} ANCHOR POINTS - NEEDS 40.",
                    style = body(11.sp, Neon.Orange))
                SegmentBar((aim.features / 40f).coerceIn(0f, 1f), Modifier.fillMaxWidth().padding(vertical = 4.dp), segments = 20)
                if (locked) ArcadeButton("ANCHOR", onAnchor, color = Neon.Lime)
            }
            !confirmed -> {
                Row {
                    ArcadeButton("✓ YES, TRACK THIS", onConfirm, color = Neon.Lime)
                    ArcadeButton("RETRY", onRetry, small = true, color = Neon.Orange)
                }
                if (shownPic != null) ArcadeButton("SAVE THIS LOCATION", { showSave = true }, small = true, color = Neon.Cyan)
            }
            else -> Text("TRACKING CONFIRMED", style = arcade(20.sp, Neon.Lime, glow = Neon.Lime))
        }
        saved?.let { (name, savedThumb) ->
            Spacer(Modifier.height(6.dp))
            Text(if (name.isNotEmpty()) "LAST TIME: \"${name.uppercase()}\"" else "A SAVED LOCATION IS ON FILE",
                style = body(10.sp, Neon.Dim))
            Image(savedThumb.asImageBitmap(), "saved location", Modifier.fillMaxWidth().height(60.dp),
                contentScale = ContentScale.Fit)
        }
    }
    if (showSave && shownPic != null) SaveLocationPopup(saved?.first ?: "") { name ->
        if (name != null) gun.saveLocation(name, shownPic)
        showSave = false
    }
}

/** Names the spot the player is standing/aiming from right now, so a future "camera check" can
 *  show it back to them ("match this picture") instead of starting from nothing. */
@Composable
private fun SaveLocationPopup(initial: String, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    PopupFrame("SAVE THIS LOCATION", Neon.Cyan, { onDone(null) }) {
        Text("Give this spot a name (\"COUCH\", \"BEDROOM\") so you can tell it apart from others on this PC.",
            style = body(12.sp, Neon.White))
        Spacer(Modifier.height(6.dp))
        BasicTextField(name, { name = it }, singleLine = true, textStyle = arcade(13.sp, Neon.Yellow),
            cursorBrush = SolidColor(Neon.Yellow),
            modifier = Modifier.fillMaxWidth().background(Color.Black).padding(8.dp))
        Spacer(Modifier.height(6.dp))
        Row {
            ArcadeButton("SAVE", { onDone(name.ifBlank { "SAVED SPOT" }) }, color = Neon.Lime)
            ArcadeButton("CANCEL", { onDone(null) }, small = true, color = Neon.Dim)
        }
    }
}
