package dev.lightgun.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import dev.lightgun.AimState
import dev.lightgun.ButtonRect
import dev.lightgun.GunController
import dev.lightgun.calib.CalibrationController
import dev.lightgun.calib.CalibrationController.Phase
import dev.lightgun.net.Pairing
import dev.lightgun.net.Protocol
import kotlinx.coroutines.delay

enum class Route(val label: String) { TUTORIAL("HOW TO"), HOME("PCs"), CALIBRATE("CALIBRATE"), PLAY("PLAY!"), SETTINGS("OPTIONS"), RESUME("WELCOME BACK") }

@Composable
fun LightGunApp(gun: GunController, route: Route, onNavigate: (Route) -> Unit, onScanQr: () -> Unit,
                onUseHost: (dev.lightgun.net.SavedHost) -> Unit) {
    var aim by remember { mutableStateOf(AimState()) }
    var cal by remember { mutableStateOf(gun.calib.state) }
    LaunchedEffect(Unit) {
        while (true) { aim = gun.aim; cal = gun.calib.state; delay(80) }
    }
    RetroTheme {
        // animated grid only in menus: during play it would compete with ARCore for the GPU/CPU
        // animated bad guys only behind menus - not during play (CPU) or the tutorial (readability)
        var splash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
        val fullScreen = route == Route.PLAY || route == Route.TUTORIAL || route == Route.RESUME
        RetroBackground(animate = splash || !fullScreen) {
            if (splash) { SplashScreen { splash = false }; return@RetroBackground }
            // keep clear of the camera cutout and any system bar that is currently showing
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (!fullScreen) TopBar(route, aim, onNavigate)
                if (gun.newScreen && !fullScreen)
                    WarningBanner("NEW SCREEN (${gun.displayLabel}) - SET IT UP: CALIBRATE > 1 MEASURE YOUR TV, THEN 3 LOCK ON",
                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                Box(Modifier.weight(1f)) {
                    when (route) {
                        Route.HOME -> HomeScreen(gun, aim, onScanQr, onUseHost, onRecalibrate = { h ->
                            if (gun.hostId != h.id || gun.link?.connected != true) h.pairing?.let { gun.connect(it) }
                            onNavigate(Route.CALIBRATE)
                        })
                        Route.CALIBRATE -> CalibrateScreen(gun, cal, aim, onNavigate)
                        Route.PLAY -> PlayScreen(gun, aim, cal, onNavigate)
                        Route.SETTINGS -> SettingsScreen(gun, aim)
                        Route.RESUME -> ResumeScreen(gun, aim, cal, onPlay = { onNavigate(Route.PLAY) },
                            onExit = { onNavigate(Route.HOME) })
                        Route.TUTORIAL -> TutorialScreen(gun, aim, cal, onScanQr) {
                            onNavigate(if (gun.aim.calibrated || gun.calib.lastResultOk) Route.PLAY else Route.CALIBRATE)
                        }
                    }
                    // ray-gun blast / reload sweep on top of everything (draw-only, touches pass through)
                    if (gun.settings.shotFx) ShotFxOverlay(gun.fx, fromRight = route != Route.PLAY || gun.settings.triggerOnRight)
                }
            }
        }
    }
}

/** Start-up title: the RetroSight logo slams in, shines, TAP TO START blinks. Tap or wait to skip. */
@Composable
private fun SplashScreen(onDone: () -> Unit) {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < 3.4f) withFrameNanos { t = (it - start) / 1e9f }
        onDone()
    }
    Canvas(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) { onDone() }) {
        val zoom = if (t < 0.45f) 1f + 2.5f * (1f - t / 0.45f).let { it * it } else 1f
        val a = (t / 0.2f).coerceIn(0f, 1f) * ((3.4f - t) / 0.4f).coerceIn(0f, 1f)
        val shine = ((t - 0.6f) / 1.2f).let { if (it in 0f..1.1f) it else -1f }
        drawRetroSightLogo(androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.42f), size.width * 0.82f * zoom, shine, a)
        if (t in 0.45f..0.75f) drawRect(Color.White.copy(alpha = (0.75f - t) / 0.3f * 0.6f))   // impact flash
        if (t > 1f && (t * 2.2f).toInt() % 2 == 0) {
            val msg = "TAP TO START"
            val px = size.height * 0.013f
            drawArcadeText(msg, androidx.compose.ui.geometry.Offset(size.width / 2f - PixelFont.width(msg) * px / 2f, size.height * 0.8f),
                px, PixelFont.fire, depth = Color(0xFF5A0A10), alpha = a, lean = 0f, extrude = 1)
        }
    }
}

@Composable
private fun TopBar(route: Route, a: AimState, onNavigate: (Route) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Neon.Night.copy(alpha = 0.96f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            ChromeTitle("RETRO", 14.sp)
            ChromeTitle("SIGHT ✦", 14.sp)
        }
        Spacer(Modifier.width(6.dp))
        Route.entries.filter { it != Route.RESUME }.forEach { r ->
            ArcadeButton(r.label, { onNavigate(r) }, selected = r == route,
                color = when (r) { Route.PLAY -> Neon.Lime; Route.HOME -> Neon.Cyan
                    Route.CALIBRATE -> Neon.Magenta; Route.SETTINGS -> Neon.Yellow; Route.TUTORIAL -> Neon.Orange
                    Route.RESUME -> Neon.Lime })
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Row {
                Led(a.connected, Neon.Lime, "LINK")
                Led(a.tracking && !a.gyroOnly, if (a.gyroOnly) Neon.Yellow else Neon.Cyan, if (a.gyroOnly) "GYRO" else "TRACK")
                Led(a.calibrated, Neon.Magenta, "LOCK")
            }
            Text("${a.rateHz.toInt()}HZ  AR${a.arFps.toInt()}  " +
                (if (a.rttMs >= 0) "${"%.0f".format(a.rttMs / 2)}MS" else "--MS"), style = arcade(10.sp, Neon.Dim))
        }
    }
}

// ------------------------------------------------------------------------------ Pair

// ------------------------------------------------------------------------------ Calibrate

/** Plain-language names for the host's calibration flows. */
private fun flowName(flow: String) = when (flow) {
    "A" -> "MEASURE YOUR TV"
    "B" -> "HOLD IT LIKE A GUN"
    "C" -> "LOCK ON"
    "D" -> "MEASURE BY SHOOTING"
    "Q" -> "LOCK ON"
    else -> "CALIBRATION"
}

private enum class StepState(val label: String, val color: Color) {
    DONE("✓ DONE", Neon.Lime), NEEDED("NEEDED", Neon.Orange), OPTIONAL("OPTIONAL", Neon.Yellow)
}

/** One setup step: number, name, what it does, status chip and its start button. */
@Composable
private fun SetupStep(n: String, title: String, what: String, state: StepState, next: Boolean, color: Color,
                      button: String, enabled: Boolean, onStart: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
        .background(if (next) color.copy(alpha = 0.16f) else Neon.Panel)
        .bevel(if (next) color else Neon.Dim.copy(alpha = 0.6f), if (next) 3.dp else 2.dp)
        .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(n, style = arcade(26.sp, if (state == StepState.DONE) Neon.Lime else color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = arcade(14.sp, Neon.White))
                Spacer(Modifier.width(8.dp))
                Text(" ${state.label} ", Modifier.background(state.color), style = arcade(10.sp, Neon.Night))
                if (next) Text("  ◀ NEXT", style = arcade(10.sp, color))
            }
            Text(what, style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold).copy(lineHeight = 14.sp))
        }
        ArcadeButton(if (state == StepState.DONE) "REDO" else button, onStart, small = true,
            color = if (next) color else Neon.Dim, enabled = enabled)
    }
}

@Composable
private fun CalibrateScreen(gun: GunController, cal: CalibrationController.State, aim: AimState,
                            onNavigate: (Route) -> Unit) {
    val measured = gun.hostKnowsSize && !gun.newScreen
    val grip = gun.settings.calibratedBarrel != null
    val locked = aim.calibrated && !gun.newScreen
    val s1 = if (measured) StepState.DONE else StepState.NEEDED
    val s2 = if (grip) StepState.DONE else StepState.OPTIONAL
    val s3 = if (locked) StepState.DONE else StepState.NEEDED
    val nextStep = when { !measured -> 1; !locked -> 3; else -> 0 }
    val busy = gun.calib.active
    val can = aim.connected && !busy
    var showMore by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize()) {
        ScrollColumn(Modifier.weight(1.45f).padding(8.dp).background(Neon.Night.copy(alpha = 0.92f)).padding(8.dp)) {
            if (!aim.connected) Text("NOT CONNECTED - PICK A PC ON THE PCs TAB FIRST.",
                style = arcade(12.sp, Neon.Orange))

            // ---- live progress of whatever is running / just finished
            if (cal.phase != Phase.IDLE) {
                RetroPanel(when (cal.phase) {
                    Phase.SHOOTING -> "${flowName(cal.flow)} · TARGET ${cal.index + 1}/${cal.total}"
                    Phase.SCANNING -> if (cal.flow == "Q") "LOCKING ON..." else "MEASURING YOUR TV..."
                    Phase.DONE -> "${flowName(cal.flow)} · DONE!"
                    Phase.FAILED -> "${flowName(cal.flow)} · DIDN'T WORK"
                    else -> "LOADING..."
                }, accent = when (cal.phase) { Phase.DONE -> Neon.Lime; Phase.FAILED -> Neon.Red; else -> Neon.Cyan }) {
                    if (cal.message.isNotEmpty()) Text(cal.message.uppercase(), style = arcade(12.sp, Neon.White, weight = FontWeight.Bold))
                    when (cal.phase) {
                        Phase.SCANNING -> Text(if (cal.flow == "Q") "POINT THE BACK CAMERA AT THE TV (PHONE SIDEWAYS, ITS SCREEN FACING YOU) SO IT SEES THE BLACK-AND-WHITE SQUARES."
                            else "FOLLOW THE ARROWS ON THE RIGHT: SLIDE THE PHONE SLOWLY, KEEP THE MIDDLE SQUARE IN VIEW.",
                            style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
                        Phase.SHOOTING -> {
                            Text("AIM AT THE RED CROSSHAIR ON THE TV, HOLD STILL, FIRE.", style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
                            SegmentBar(cal.index.toFloat() / cal.total.coerceAtLeast(1), Modifier.fillMaxWidth().padding(top = 4.dp),
                                segments = cal.total.coerceIn(1, 20), color = Neon.Magenta)
                            TargetScope(cal.targetX, cal.targetY, aim.x, aim.y,
                                Modifier.padding(top = 6.dp).width(200.dp).aspectRatio(16f / 9f))
                        }
                        Phase.FAILED -> Text("TRY AGAIN BELOW. TIPS: GOOD LIGHT, MOVE SLOWLY, HOLD STILL WHEN FIRING.",
                            style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
                        Phase.DONE -> if (nextStep == 0) ArcadeButton("▶ PLAY", { onNavigate(Route.PLAY) }, color = Neon.Lime)
                        else -> {}
                    }
                    if (busy) ArcadeButton("✖ CANCEL", { gun.cancelCalibration() }, small = true, color = Neon.Red)
                }
                Spacer(Modifier.height(6.dp))
            }

            // ---- the three setup steps, in order
            Text(when (nextStep) {
                1 -> "SET UP THIS SCREEN: DO STEP 1, THEN STEP 3."
                3 -> "NEARLY THERE: DO STEP 3 (4 QUICK SHOTS)."
                else -> "ALL SET! TAP PLAY - OR REDO A STEP IF THE AIM FEELS OFF."
            }, style = arcade(12.sp, Neon.Yellow))
            SetupStep("1", "MEASURE YOUR TV",
                "Phone in your hand, camera at the TV. It learns where the TV is and how big. Once per screen.",
                s1, nextStep == 1, Neon.Cyan, "START", can) { gun.startCalibration("A") }
            SetupStep("2", "HOLD IT LIKE A GUN",
                "Shoot 5 targets holding the phone how you'll play, so 'forward' matches your grip. " +
                    "Skip it and you aim with the back camera. Redo if you change grip.",
                s2, false, Neon.Magenta, "START", can) { gun.startCalibration("B") }
            SetupStep("3", "LOCK ON",
                "Point the BACK CAMERA at the TV for a second (phone sideways, screen facing you) - it finds the screen by itself. PLAY does this " +
                    "automatically, also after the phone sleeps.",
                s3, nextStep == 3, Neon.Lime, "START", can && measured) { gun.startCalibration("Q") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (!measured) "STEP 3 UNLOCKS AFTER STEP 1.  " else "AIM SLIGHTLY OFF?  ",
                    style = arcade(9.sp, Neon.Dim, weight = FontWeight.Bold))
                if (measured) ArcadeButton("LOCK ON BY SHOOTING 4 CORNERS", { gun.startCalibration("C") }, small = true,
                    color = Neon.Dim, enabled = can)
            }

            Spacer(Modifier.height(4.dp))
            ArcadeButton(if (showMore) "▲ HIDE" else "STEP 1 KEEPS FAILING?", { showMore = !showMore }, small = true, color = Neon.Dim)
            if (showMore) {
                Text("MEASURE BY SHOOTING does steps 1 and 2 without scanning the TV: shoot 9 targets, " +
                        "then walk to a different spot and shoot 9 more. Slower (18 shots), but works with glare or a dark room.",
                    style = arcade(10.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 14.sp))
                ArcadeButton("MEASURE BY SHOOTING", { gun.startCalibration("D") }, small = true, color = Neon.Orange, enabled = can)
                Text("NOW AIMING WITH: ${gun.settings.effectiveProfileName().uppercase()}", style = arcade(9.sp, Neon.Dim))
            }
        }
        var coach by remember { mutableStateOf(gun.coach.state) }
        LaunchedEffect(Unit) { while (true) { coach = gun.coach.state; delay(80) } }
        if (cal.phase == Phase.SCANNING && coach.active)
            ScanCoachView(coach, cal.progress.toFloat(), Modifier.weight(1.2f).fillMaxHeight().padding(8.dp))
        else ArcadeZone("FIRE!", "HOLD STILL · SQUEEZE", Neon.Red, Modifier.weight(1f).fillMaxHeight(),
            { gun.setButton(Protocol.BTN_TRIGGER, it) })
    }
}

// ------------------------------------------------------------------------------ Play

/**
 * Starting position/size for each Play-screen button, as fractions of the whole screen, matching
 * the look of the old fixed Row/Column layout. [gun.settings.playLayout] overrides any of these
 * once the player drags or resizes a zone; this is only the layout before that ever happens.
 */
private fun defaultPlayLayout(triggerOnRight: Boolean): Map<String, ButtonRect> {
    // trigger side: a big FIRE pad in the lower part (where the thumb rests) with RELOAD above it.
    // middle: the status light and menu. other side: A / B / COIN / START as an easy 2x2 grid.
    val sw = 0.32f; val hw = 1f - 2 * sw
    val fireX = if (triggerOnRight) sw + hw else 0f
    val padX = if (triggerOnRight) 0f else sw + hw
    val fireTop = 0.36f
    val half = sw / 2
    return mapOf(
        "fire" to ButtonRect(fireX, fireTop, sw, 1f - fireTop),
        "reload" to ButtonRect(fireX, 0f, sw, fireTop),
        "hud" to ButtonRect(sw, 0f, hw, 1f),
        "a" to ButtonRect(padX, 0f, half, 0.5f),
        "b" to ButtonRect(padX + half, 0f, half, 0.5f),
        "coin" to ButtonRect(padX, 0.5f, half, 0.5f),
        "start" to ButtonRect(padX + half, 0.5f, half, 0.5f),
    )
}

@Composable
private fun PlayScreen(gun: GunController, aim: AimState, cal: CalibrationController.State, onNavigate: (Route) -> Unit) {
    DisposableEffect(Unit) { gun.playing = true; onDispose { gun.playing = false; gun.endTrim(); gun.freeMouse(false) } }
    // every quick lock-on in Play (PLAY tap, after the phone was put down/slept) ends with one
    // centre-target shot that trims the remaining offset, then the game is back
    var seenSeq by remember { mutableStateOf(gun.calib.resultSeq) }
    LaunchedEffect(cal.phase, cal.flow) {
        val seq = gun.calib.resultSeq
        if (seq == seenSeq) return@LaunchedEffect
        seenSeq = seq
        if (cal.phase == Phase.DONE && cal.flow == "Q") { delay(300); gun.startTrim() }
    }
    // Auto lock on entry and whenever the screen is lost (phone slept, world reset): point the
    // gun at the TV (camera quick lock). Falls back to the 4-corner shots if that fails.
    // PLAY from the PCs screen: one quick lock-on even if already locked (you may be standing
    // somewhere else than last time), then straight into the game.
    LaunchedEffect(aim.connected) {
        if (!gun.lockOnPlay || !aim.connected) return@LaunchedEffect
        // wait briefly for the PC's hello reply so we know whether it knows the TV size
        var waited = 0
        while (!gun.hostKnowsSize && waited < 2000) { delay(100); waited += 100 }
        if (!gun.lockOnPlay) return@LaunchedEffect
        gun.lockOnPlay = false
        if (gun.calib.active || gun.newScreen) return@LaunchedEffect
        delay(500)
        gun.startCalibration(if (gun.hostKnowsSize) "Q" else "C")
    }
    LaunchedEffect(aim.connected, aim.calibrated, cal.phase == Phase.FAILED) {
        if (gun.lockOnPlay) return@LaunchedEffect   // the PLAY lock-on above handles it
        if (aim.connected && !aim.calibrated && gun.settings.autoReanchor && !gun.calib.active && !gun.newScreen) {
            delay(700)
            if (gun.aim.calibrated || gun.calib.active) return@LaunchedEffect
            val quickFailed = cal.phase == Phase.FAILED && cal.flow == "Q"
            if (cal.phase == Phase.FAILED && !quickFailed) return@LaunchedEffect  // don't loop on C failures
            gun.startCalibration(if (gun.hostKnowsSize && !quickFailed) "Q" else "C")
        }
    }
    // picked the phone back up and it found the TV again: a buzz to go with the big green light
    var wasSolid by remember { mutableStateOf(aim.solid) }
    LaunchedEffect(aim.solid) {
        if (aim.solid && !wasSolid) gun.haptics.reload()
        wasSolid = aim.solid
    }
    val right = gun.settings.triggerOnRight
    // what each pad does in the emulator in front (sent by the PC), e.g. A = "BUTTON 2" in MAME
    val labels = gun.emuStatus?.takeIf { System.currentTimeMillis() - it.atMs < 10_000 }?.labels ?: emptyMap()
    var showEmu by remember { mutableStateOf(false) }
    var showNet by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var editLayout by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf(gun.settings.playLayout) }
    fun setRect(id: String, r: ButtonRect) {
        layout = layout + (id to r)
        gun.settings.playLayout = layout
    }
    val defaults = defaultPlayLayout(right)
    Box(Modifier.fillMaxSize()) {
        EditableLayout(Modifier.fillMaxSize()) { container ->
            // middle: HUD, fixed in place and drawn FIRST, so a button dragged over the middle
            // sits on top of it and still works; long-press on an empty part = recenter
            val hud = defaults["hud"]!!
            Box(Modifier
                .offset { IntOffset((hud.x * container.width).roundToInt(), (hud.y * container.height).roundToInt()) }
                .size(with(LocalDensity.current) { (hud.w * container.width).toDp() },
                      with(LocalDensity.current) { (hud.h * container.height).toDp() })) {
                ArcadeZone("", "", Neon.Dim.copy(alpha = 0.5f), Modifier.fillMaxSize(), {},
                    onLongPress = { gun.recenter() }, interactive = !editLayout)
                Hud(gun, aim, cal, onNavigate, onEmu = { showEmu = true }, onInvite = { showInvite = true },
                    onNet = { showNet = true }, editLayout = editLayout,
                    onToggleEditLayout = { editLayout = !editLayout },
                    onResetLayout = { layout = emptyMap(); gun.settings.playLayout = emptyMap() })
            }
            MovableZone("fire", layout["fire"] ?: defaults["fire"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("FIRE!", "OFF-SCREEN = RELOAD", Neon.Red, m,
                    { gun.setButton(Protocol.BTN_TRIGGER, it) }, interactive = !editLayout)
            }
            MovableZone("reload", layout["reload"] ?: defaults["reload"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("RELOAD", labels["reload"]?.let { "= $it" } ?: "OR FLICK THE GUN", Neon.Cyan, m,
                    { gun.setButton(Protocol.BTN_RELOAD, it) }, interactive = !editLayout)
            }
            MovableZone("a", layout["a"] ?: defaults["a"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("A", labels["a"] ?: "", Neon.Lime, m, { gun.setButton(Protocol.BTN_A, it) }, interactive = !editLayout)
            }
            MovableZone("b", layout["b"] ?: defaults["b"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("B", labels["b"] ?: "", Neon.Yellow, m, { gun.setButton(Protocol.BTN_B, it) }, interactive = !editLayout)
            }
            MovableZone("coin", layout["coin"] ?: defaults["coin"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("COIN", labels["select"] ?: "SELECT", Neon.Orange, m,
                    { gun.setButton(Protocol.BTN_SELECT, it) }, interactive = !editLayout)
            }
            MovableZone("start", layout["start"] ?: defaults["start"]!!, container, editLayout, onRectChange = ::setRect) { m ->
                ArcadeZone("ST", labels["start"] ?: "START", Neon.Magenta, m,
                    { gun.setButton(Protocol.BTN_START, it) }, interactive = !editLayout)
            }
        }
        if (gun.recalibrating) RecalibrateOverlay(gun, aim)
        // buttons still reach the PC while this is open, so it doubles as a button test
        if (showEmu) EmuPopup(gun) { showEmu = false }
        if (showNet) NetPopup(aim) { showNet = false }
        if (showInvite) InvitePopup(gun) { showInvite = false }
    }
}

/** "Is the emulator seeing the gun?" chip for the Play HUD; tap for the full PC check. */
@Composable
private fun EmuChip(gun: GunController, onOpen: () -> Unit) {
    val st = gun.emuStatus?.takeIf { System.currentTimeMillis() - it.atMs < 10_000 }
    val (text, color) = when {
        st == null -> "NO EMULATOR IN FRONT" to Neon.Dim
        st.problems.isEmpty() -> "${st.emu.uppercase()} ✓ SEES GUN" to Neon.Lime
        else -> "${st.emu.uppercase()}: ${st.problems.size} PROBLEM${if (st.problems.size > 1) "S" else ""} ▸" to Neon.Red
    }
    ArcadeButton(text, onOpen, small = true, color = color)
    if (st != null && st.game.isNotEmpty())
        Text("LOADED: " + st.game.uppercase(), maxLines = 2, textAlign = TextAlign.Center,
            style = arcade(10.sp, if (st.problems.isEmpty()) Neon.Lime else Neon.Orange, weight = FontWeight.Bold))
    gun.inputEcho?.takeIf { System.currentTimeMillis() - it.third < 2500 }?.let { (b, sent) ->
        Text("$b → ${sent.uppercase()}", style = arcade(10.sp, Neon.Yellow, glow = Neon.Orange))
    }
}

private fun netBad(a: AimState) = a.connected && (a.netLoss > 3.0 || a.netLateMs > 45.0)

/** Shows only when something hurts the aim: weak Wi-Fi or an overheating phone. */
@Composable
private fun NetChip(aim: AimState, onOpen: () -> Unit) {
    when {
        netBad(aim) -> ArcadeButton("WI-FI WEAK ▸", onOpen, small = true, color = Neon.Orange)
        aim.hot -> ArcadeButton("PHONE HOT ▸", onOpen, small = true, color = Neon.Orange)
    }
}

@Composable
private fun NetPopup(aim: AimState, onClose: () -> Unit) {
    PopupFrame(if (aim.hot && !netBad(aim)) "PHONE IS HOT" else "WI-FI IS STRUGGLING", Neon.Orange, onClose) {
        if (netBad(aim)) {
            Text("Right now: %.0f%% of aim updates lost, up to %.0f ms late (ping %.0f ms)."
                .format(aim.netLoss, aim.netLateMs, aim.rttMs), style = arcade(12.sp, Neon.Yellow, weight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))
            listOf(
                "Use 5 GHz Wi-Fi (network names ending in 5G) - 2.4 GHz is crowded and slow to react.",
                "Plug the PC into the router with a cable if you can - then only the phone is on Wi-Fi.",
                "Stand closer to the router, or with fewer walls in between.",
                "Turn Bluetooth off on the phone if you don't use a BT trigger - it shares the 2.4 GHz radio.",
                "Turn off Battery Saver / Power Saving on the phone.",
                "Pause big downloads, streaming or game updates on the same network.",
            ).forEach { Text("• $it", style = arcade(11.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 16.sp)) }
            Text("Missed taps are replayed when they arrive late, and a stuck trigger is let go automatically.",
                style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
        }
        if (aim.hot) {
            Spacer(Modifier.height(4.dp))
            Text("The phone is getting hot, so Android slows the camera and tracking gets choppy. Take the case off, " +
                    "turn the screen brightness down, keep it off the charger while playing, or let it cool for a few minutes.",
                style = arcade(11.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 16.sp))
        }
        ArcadeButton("CLOSE", onClose, small = true, color = Neon.Dim)
    }
}

@Composable
private fun EmuPopup(gun: GunController, onClose: () -> Unit) {
    val st = gun.emuStatus
    PopupFrame(if (st == null) "EMULATOR CHECK" else st.emu.uppercase() + (if (st.game.isNotEmpty()) " · ${st.game.uppercase()}" else ""),
        if (st == null || st.problems.isNotEmpty()) Neon.Orange else Neon.Lime, onClose) {
        if (st == null) {
            Text("Bring the emulator window to the front on the PC. The PC app then checks its gun settings " +
                    "and shows the result here.", style = arcade(12.sp, Neon.White, weight = FontWeight.Bold))
        } else {
            st.checks.forEach { c ->
                Text((if (c.ok) "✓ " else "✗ ") + c.text, style = arcade(11.sp, if (c.ok) Neon.Lime else Neon.Red,
                    weight = FontWeight.Bold))
                if (!c.ok && c.fix.isNotEmpty()) Text("   → " + c.fix, style = arcade(10.sp, Neon.White,
                    weight = FontWeight.Bold).copy(lineHeight = 15.sp))
            }
            when (st.cursorOk) {
                true -> Text("✓ PC cursor follows the gun", style = arcade(11.sp, Neon.Lime, weight = FontWeight.Bold))
                false -> Text("✗ PC cursor is NOT following the gun - emulator running as administrator? Run " +
                        "start-host.bat as administrator too.", style = arcade(11.sp, Neon.Red, weight = FontWeight.Bold))
                null -> {}
            }
        }
        if (st != null && (st.emu == "MAME" || st.emu == "RetroArch")) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArcadeButton(if (st.problems.isEmpty()) "RE-APPLY GUN SETTINGS" else "FIX IT ON THE PC",
                    { gun.requestEmuFix() }, small = true, color = if (st.problems.isEmpty()) Neon.Dim else Neon.Lime)
            }
            Text("Changes ${st.emu}'s settings files on the PC for the gun (a backup is kept)." +
                    if (st.emu == "RetroArch") " Applies to the last game you loaded." else "",
                style = arcade(9.sp, Neon.Dim, weight = FontWeight.Bold))
            gun.emuFixResult?.let { Text(it.uppercase(), style = arcade(11.sp, Neon.Yellow, weight = FontWeight.Bold)) }
        }
        Spacer(Modifier.height(6.dp))
        Text("BUTTON TEST: press any button - the PC shows what it sent (e.g. COIN → KEY 5).",
            style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
        gun.inputEcho?.let { (b, sent) -> Text("LAST: $b → ${sent.uppercase()}", style = arcade(12.sp, Neon.Yellow)) }
        ArcadeButton("CLOSE", onClose, small = true, color = Neon.Dim)
    }
}

/** One big light that says whether you can play, and the single thing to do if not. */
private data class Light(val color: Color, val title: String, val line: String, val blink: Boolean = false)

private fun lightFor(gun: GunController, aim: AimState, cal: CalibrationController.State): Light = when {
    gun.mousePaused -> Light(Neon.Cyan, "MOUSE FREED", "The gun is off. Tap GUN ON to play.")
    !aim.connected -> Light(Neon.Red, "NO PC", "Start RetroSight on the PC (same Wi-Fi).", blink = true)
    gun.newScreen && !gun.calib.active -> Light(Neon.Red, "NEW SCREEN", "Tap MENU and measure this TV.")
    gun.trimming -> Light(Neon.Lime, "SHOOT THE CENTER", "One shot at the target in the middle of the TV.", blink = true)
    gun.calib.active -> Light(Neon.Yellow, "LOCKING ON...", cal.message.ifEmpty { "Point the back camera at the TV." }, blink = true)
    cal.phase == Phase.FAILED && !aim.calibrated -> Light(Neon.Red, "LOCK-ON FAILED", cal.message.ifEmpty { "Tap TRY AGAIN." })
    !aim.calibrated -> Light(Neon.Red, "NOT LOCKED", "Point the back camera at the TV.", blink = true)
    !aim.tracking || aim.gyroOnly -> Light(Neon.Yellow, "CAMERA LOST THE ROOM",
        "Point it at the floor or furniture for a second.", blink = true)
    aim.guessed -> Light(Neon.Yellow, "AIM MAY BE OFF",
        "Let the camera see the room again, or tap RECALIBRATE.")
    else -> Light(Neon.Lime, "LOCKED ON ✓", if (aim.onScreen) "Ready - fire away!" else "Aim at the TV.")
}

@Composable
private fun StatusLight(l: Light, modifier: Modifier) {
    Blink(900) { a ->
        val alpha = if (l.blink) 0.55f + 0.45f * a else 1f
        Column(modifier.background(l.color.copy(alpha = 0.22f * alpha)).bevel(l.color, 3.dp).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(26.dp).alpha(alpha).background(l.color, androidx.compose.foundation.shape.CircleShape))
            Text(l.title, textAlign = TextAlign.Center, style = arcade(17.sp, l.color, glow = l.color))
            Text(l.line, textAlign = TextAlign.Center,
                style = arcade(10.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 13.sp))
        }
    }
}

@Composable
private fun Hud(gun: GunController, aim: AimState, cal: CalibrationController.State, onNavigate: (Route) -> Unit,
                onEmu: () -> Unit, onInvite: () -> Unit, onNet: () -> Unit, editLayout: Boolean,
                onToggleEditLayout: () -> Unit, onResetLayout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        if (editLayout) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DRAG A BUTTON TO MOVE IT - ANYWHERE, EVEN HERE", textAlign = TextAlign.Center,
                style = arcade(11.sp, Neon.Yellow, glow = Neon.Yellow))
            Text("DRAG ITS YELLOW CORNER TO RESIZE", style = arcade(9.sp, Neon.Dim))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StatusLight(lightFor(gun, aim, cal), Modifier.fillMaxWidth())
            // the one action that fixes the light, right under it
            when {
                gun.trimming && !gun.recalibrating -> ArcadeButton("SKIP", { gun.endTrim() }, small = true, color = Neon.Dim)
                gun.calib.active && cal.flow == "Q" -> ArcadeButton("SHOOT 4 CORNERS INSTEAD", {
                    gun.cancelCalibration(); gun.startCalibration("C") }, small = true, color = Neon.Dim)
                cal.phase == Phase.FAILED && !aim.calibrated && !gun.calib.active ->
                    ArcadeButton("TRY AGAIN", { gun.startCalibration(if (gun.hostKnowsSize) "Q" else "C") }, small = true, color = Neon.Lime)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(" P${gun.player} ", Modifier.background(if (gun.player == 1) Neon.Red else Neon.Cyan),
                    style = arcade(12.sp, Neon.Night))
                Text("  %06d".format(aim.shots * 100 + aim.reloads * 10), style = arcade(16.sp, Neon.Yellow, glow = Neon.Orange))
            }
            EmuChip(gun, onEmu)
            NetChip(aim, onNet)
            if (aim.lastEvent.isNotEmpty()) Text(aim.lastEvent.uppercase(), textAlign = TextAlign.Center,
                style = arcade(9.sp, Neon.Cyan, weight = FontWeight.Bold))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (editLayout) Row {
                ArcadeButton("RESET", onResetLayout, small = true, color = Neon.Orange)
                ArcadeButton("DONE", onToggleEditLayout, small = true, color = Neon.Lime)
            } else {
                // pauses the game; aim at the TV's centre target and hold a button - then straight back
                if (aim.connected && aim.calibrated && !gun.newScreen && !gun.calib.active && !gun.trimming)
                    ArcadeButton("RECALIBRATE", { gun.startTrim(pause = true) }, color = Neon.Magenta)
                Row {
                    if (aim.connected)
                        ArcadeButton(if (gun.mousePaused) "GUN ON" else "FREE MOUSE",
                            { gun.freeMouse(!gun.mousePaused) }, small = true,
                            color = if (gun.mousePaused) Neon.Lime else Neon.Cyan)
                    ArcadeButton("MENU", { onNavigate(Route.CALIBRATE) }, small = true, color = Neon.Dim)
                }
                Row {
                    ArcadeButton("LAYOUT", onToggleEditLayout, small = true, color = Neon.Dim)
                    if (gun.player == 1 && aim.connected) ArcadeButton("+P2", onInvite, small = true, color = Neon.Cyan)
                }
                Text("BATT ${aim.battery}%  ${if (aim.rttMs >= 0) "%.0fMS".format(aim.rttMs / 2) else "--"}",
                    style = arcade(8.sp, Neon.Dim))
            }
        }
    }
}

/**
 * RECALIBRATE: the game is paused on the PC and a target shows in the middle of the TV. Aim at
 * it and hold the big button for 2 seconds (or pull the trigger) - then it's straight back to
 * the game.
 */
@Composable
private fun RecalibrateOverlay(gun: GunController, aim: AimState) {
    Box(Modifier.fillMaxSize().background(Neon.Night.copy(alpha = 0.94f)), Alignment.Center) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("GAME PAUSED", style = arcade(26.sp, Neon.Magenta, glow = Neon.Magenta))
                Spacer(Modifier.height(10.dp))
                Text("1. Point the gun at the target in the middle of the TV.", textAlign = TextAlign.Center,
                    style = arcade(14.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 19.sp))
                Spacer(Modifier.height(6.dp))
                Text("2. Hold the big button for 2 seconds.", textAlign = TextAlign.Center,
                    style = arcade(14.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 19.sp))
                Spacer(Modifier.height(6.dp))
                Text("The game starts again by itself.", textAlign = TextAlign.Center, style = arcade(11.sp, Neon.Dim))
                if (aim.lastEvent.startsWith("recenter refused")) Text(
                    "TOO FAR FROM THE CENTER - AIM AT THE TARGET AND HOLD AGAIN", textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp), style = arcade(11.sp, Neon.Orange, glow = Neon.Orange))
                Spacer(Modifier.height(12.dp))
                ArcadeButton("CANCEL - BACK TO GAME", { gun.endTrim() }, small = true, color = Neon.Dim)
            }
            ArcadeZone("HOLD", "2 SECONDS", Neon.Magenta, Modifier.weight(1f).fillMaxHeight(), {},
                onLongPress = { if (gun.recenter()) gun.endTrim() }, longPressMs = 2000)
        }
    }
}

// ------------------------------------------------------------------------------ Settings

@Composable
private fun SettingsScreen(gun: GunController, aim: AimState) {
    val s = gun.settings
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        gun.setThumbnailListener { thumb = it }
        onDispose { gun.setThumbnailListener(null) }
    }
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        ScrollColumn(Modifier.weight(1.3f)) {
            RetroPanel("RELOAD", accent = Neon.Cyan) {
                RetroSwitch("Shoot off-screen = reload", s.offscreenReload) { s.offscreenReload = it; gun.applySettings() }
                RetroSwitch("Quick flick = reload", s.flickReload) { s.flickReload = it; gun.applySettings() }
                RetroSlider("Flick threshold (lower = touchier)", s.flickThreshold, 4f..20f, "%.1f") {
                    s.flickThreshold = it; gun.applySettings()
                }
            }
            RetroPanel("RUMBLE", accent = Neon.Red) {
                RetroSwitch("Vibration", s.haptics) { s.haptics = it; gun.applySettings() }
                if (!gun.haptics.hasStrengthControl) Text("THIS MOTOR IS ON/OFF ONLY: STRENGTH SLIDERS HAVE NO EFFECT",
                    style = arcade(9.sp, Neon.Orange, weight = FontWeight.Bold))
                Text("FIRE = CLICK \u2192 BEAT \u2192 RECOIL \u2192 FADE", style = arcade(10.sp, Neon.Yellow, weight = FontWeight.Bold))
                var rumbleKey by remember { mutableStateOf(0) }
                key(rumbleKey) {  // DEFAULTS bumps this so the sliders re-read their values
                RetroSlider("Click strength", s.clickStrength, 1f..255f, "%.0f", Neon.Red) { s.clickStrength = it; gun.applySettings() }
                RetroSlider("Beat before recoil (ms)", s.beatMs, 0f..80f, "%.0f", Neon.Red) { s.beatMs = it; gun.applySettings() }
                RetroSlider("Recoil strength", s.recoilStrength, 1f..255f, "%.0f", Neon.Red) { s.recoilStrength = it; gun.applySettings() }
                RetroSlider("Recoil length (ms)", s.gunshotMs, 20f..150f, "%.0f", Neon.Red) { s.gunshotMs = it; gun.applySettings() }
                RetroSlider("Recoil fade (% of length)", s.fadePercent, 0f..200f, "%.0f", Neon.Red) { s.fadePercent = it; gun.applySettings() }
                RetroSlider("Reload click strength (0 = system click)", s.reloadStrength, 0f..255f, "%.0f", Neon.Cyan) {
                    s.reloadStrength = it; gun.applySettings()
                }
                }
                Row {
                    ArcadeButton("BANG!", { gun.haptics.gunshot() }, small = true, color = Neon.Red)
                    ArcadeButton("CLICK", { gun.haptics.reload() }, small = true, color = Neon.Cyan)
                    ArcadeButton("DEFAULTS", {
                        s.clickStrength = 255f; s.beatMs = 28f; s.recoilStrength = 255f; s.gunshotMs = 55f
                        s.fadePercent = 100f; s.reloadStrength = 0f; gun.applySettings(); rumbleKey++
                    }, small = true, color = Neon.Dim)
                }
            }
            ButtonMapPanel(gun)
            RetroPanel("AIM FEEL", accent = Neon.Magenta) {
                RetroSlider("Prediction (ms)", s.predictionMs, 0f..30f, "%.0f", Neon.Magenta) { s.predictionMs = it; gun.applySettings() }
                RetroSlider("Smoothing min cutoff (Hz)", s.minCutoff, 0.1f..5f, "%.2f", Neon.Magenta) { s.minCutoff = it; gun.applySettings() }
                RetroSlider("Smoothing speed boost", s.beta, 0f..0.05f, "%.4f", Neon.Magenta) { s.beta = it; gun.applySettings() }
                RetroSlider("Fusion gain", s.fusionGain, 0.01f..0.5f, "%.2f", Neon.Magenta) { s.fusionGain = it; gun.applySettings() }
                RetroSlider("Send rate (Hz)", s.sendRateHz.toFloat(), 60f..400f, "%.0f", Neon.Magenta) { s.sendRateHz = it.toInt(); gun.applySettings() }
            }
            RetroPanel("CABINET", accent = Neon.Yellow) {
                RetroSwitch("Fire button on the right", s.triggerOnRight) { s.triggerOnRight = it }
                RetroSwitch("Auto re-anchor on PLAY", s.autoReanchor) { s.autoReanchor = it }
                RetroSwitch("Dim screen during play", s.dimDuringPlay) { s.dimDuringPlay = it }
                RetroSwitch("Ray-gun blast on screen when firing", s.shotFx) { s.shotFx = it }
            }
            RetroPanel("SELECT YOUR GRIP", accent = Neon.Lime) {
                var active by remember { mutableStateOf(s.activeProfile) }
                s.profiles().forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArcadeButton(if (active == p.name) "▶ ${p.name}" else p.name,
                            { active = p.name; s.activeProfile = p.name; gun.applySettings() },
                            small = true, selected = active == p.name, color = Neon.Lime)
                        Text(p.note.uppercase(), style = arcade(9.sp, Neon.Dim, weight = FontWeight.Bold))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            RetroPanel("CAMERA EYE", accent = Neon.Cyan) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                    contentScale = ContentScale.Crop) }
                    ?: Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black), Alignment.Center) {
                        Blink { a -> Text("NO SIGNAL", Modifier.alpha(a), style = arcade(14.sp, Neon.Dim)) }
                    }
                Text("ANCHOR POINTS ${aim.features}", style = arcade(12.sp, if (aim.features >= 40) Neon.Lime else Neon.Orange))
                SegmentBar((aim.features / 200f).coerceIn(0f, 1f), Modifier.fillMaxWidth(), color = Neon.Lime)
                if (aim.features < 40 && (!aim.tracking || aim.gyroOnly)) Text("NOT ENOUGH ANCHOR POINTS! POINT THE CAMERA AT THE FLOOR OR FURNITURE, NOT A BLANK WALL OR CEILING.",
                    style = arcade(9.sp, Neon.Orange, weight = FontWeight.Bold))
                Text(if (aim.tracking) "LOCKED ONTO THE ROOM" else "SEARCHING FOR ANCHOR POINTS (${aim.failure?.replace('_', ' ')?.lowercase() ?: "off"})",
                    style = arcade(10.sp, if (aim.tracking) Neon.Cyan else Neon.Orange))
                Text("MAP CORRECTIONS ${aim.anchorJumps}", style = arcade(9.sp, Neon.Dim))
            }
        }
    }
}
