package dev.lightgun

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.lightgun.aim.Barrel
import dev.lightgun.aim.ScreenRect
import dev.lightgun.ar.ArEngine
import dev.lightgun.calib.ArucoScanner
import dev.lightgun.calib.CalibrationController
import dev.lightgun.calib.ScanCoach
import dev.lightgun.fusion.OneEuroVec
import dev.lightgun.fusion.PoseFuser
import dev.lightgun.fusion.Vec3
import dev.lightgun.input.FlickDetector
import dev.lightgun.input.GunAction
import dev.lightgun.input.Haptics
import dev.lightgun.net.HostLink
import dev.lightgun.net.Pairing
import dev.lightgun.net.Protocol
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Latest aim result for the HUD. */
data class AimState(
    val x: Float = 0.5f, val y: Float = 0.5f, val onScreen: Boolean = false,
    val tracking: Boolean = false, val gyroOnly: Boolean = false, val calibrated: Boolean = false,
    val failure: String? = null, val rateHz: Double = 0.0, val arFps: Double = 0.0,
    val features: Int = 0, val rttMs: Double = -1.0, val connected: Boolean = false,
    val buttons: Int = 0, val battery: Int = -1, val markers: Int = 0,
    val reloads: Int = 0, val shots: Int = 0, val flicks: Int = 0, val offscreenReloads: Int = 0,
    val recenters: Int = 0, val lastEvent: String = "", val anchorJumps: Int = 0,
    val netLoss: Double = 0.0, val netLateMs: Double = 0.0, val hot: Boolean = false,
)

/**
 * Wires everything together: gyro thread -> fuser -> ray -> filter -> screen -> UDP.
 * Process-wide singleton (orientation is locked, so the Activity rarely recreates).
 */
class GunController private constructor(private val app: Context) : SensorEventListener {
    val settings = Settings(app)
    val fuser = PoseFuser(settings.fusionGain.toDouble(), settings.predictionMs.toDouble())
    val filter = OneEuroVec(settings.minCutoff.toDouble(), settings.beta.toDouble())
    val engine = ArEngine(app, fuser)
    val calib = CalibrationController { link?.sendCtrl(it) }
    val haptics = Haptics(app)
    val flick = FlickDetector()
    /** Screen effects (ray-gun blast / reload sweep); bumped on the main thread. */
    val fx = dev.lightgun.ui.FxBus()
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    val coach = ScanCoach()
    private val scanner = ArucoScanner({ link?.sendCtrl(it) }, { ids, side, sent ->
        markersSeen = ids.size
        coach.onDetections(ids, side, sent)
    })

    @Volatile var link: HostLink? = null
        private set
    @Volatile var barrel: Barrel = settings.barrel()
    @Volatile var aim = AimState()
        private set
    /** Display the host is driving now (from hello_ack). */
    @Volatile var displayId: String? = null
        private set
    @Volatile var displayLabel: String = ""
        private set
    /** True when the host is on a different monitor than the one we calibrated for. */
    @Volatile var newScreen = false
        private set

    /** One line of the PC's "is the emulator seeing the gun?" check. */
    data class EmuCheck(val ok: Boolean, val text: String, val fix: String)
    /** Latest emulator check from the PC (null = no emulator seen yet). */
    data class EmuStatus(val emu: String, val game: String, val checks: List<EmuCheck>, val cursorOk: Boolean?,
                         val atMs: Long, val labels: Map<String, String> = emptyMap()) {
        val problems get() = checks.filter { !it.ok } +
            (if (cursorOk == false) listOf(EmuCheck(false, "PC cursor is not following the gun",
                "Emulator running as administrator? Run start-host.bat as administrator too.")) else emptyList())
    }
    @Volatile var emuStatus: EmuStatus? = null
    /** Last button press as the PC turned it into input: "COIN/SELECT" to "key 5". */
    @Volatile var inputEcho: Triple<String, String, Long>? = null
    /** Reply to "FIX IT": what the PC changed (null = none yet / waiting). */
    @Volatile var emuFixResult: String? = null

    /** 1 for the first gun on this PC, 2 for a phone that joined as player 2, ... */
    val player get() = (settings.pairing?.gunId ?: 0) + 1

    /** Pairing code that joins another phone to this PC as the next player. */
    fun invite(player: Int): String? = settings.pairing?.copy(gunId = player - 1)?.toUri()

    fun requestEmuFix() {
        emuFixResult = "Fixing..."
        link?.sendCtrl(JSONObject().put("type", "emu_fix"))
    }
    /** Does the host already know this display's physical size (i.e. was it measured/entered)? */
    val hostKnowsSize get() = link?.lastHelloAck?.let { !it.isNull("screen_size_m") } == true

    /** Set by the UI: true while the Play screen is showing (enables auto re-anchor). */
    @Volatile var playing = false

    /** Set when PLAY is tapped on a PC: the Play screen runs one quick lock-on first, then the game. */
    @Volatile var lockOnPlay = false

    /** After a quick lock-on: the next trigger pull at the TV's centre target trims the aim. */
    @Volatile var trimming = false
        private set
    private var trimToken = 0

    fun startTrim() {
        if (link?.connected != true) return
        trimming = true
        val token = ++trimToken
        link?.sendCtrl(JSONObject().put("type", "trim").put("on", true))
        mainHandler.postDelayed({ if (trimming && trimToken == token) endTrim() }, 12_000)  // don't hang around
    }

    fun endTrim() {
        if (!trimming) return
        trimming = false
        val off = { link?.sendCtrl(JSONObject().put("type", "trim").put("on", false)) }
        off(); mainHandler.postDelayed({ if (!trimming) off() }, 300)   // UDP can drop one
    }

    /** Physical key -> action, cached from settings. */
    @Volatile var buttonMap: Map<Int, GunAction> = settings.buttonMap
        private set
    /** Set by the Options screen: the next key press is reported here instead of acting. */
    @Volatile var learnKey: ((Int) -> Unit)? = null

    // cached settings (SharedPreferences is too slow for the sensor thread)
    @Volatile private var sendPeriodNs = 5_000_000L
    @Volatile private var offscreenReload = true

    private val buttons = AtomicInteger(0)
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wifiLock: WifiManager.WifiLock = (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lightgun").apply {
            setReferenceCounted(false)
        }
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var timers: ScheduledExecutorService? = null
    private var lastSendNs = 0L
    private var sentInWindow = 0
    private var windowStart = 0L
    private var rate = 0.0
    private var reloads = 0
    private var shots = 0
    private var flicks = 0
    private var offscreenReloads = 0
    private var recenters = 0
    @Volatile private var lastEvent = ""
    @Volatile private var markersSeen = 0
    @Volatile private var lastRay: Pair<Vec3, Vec3>? = null
    @Volatile private var triggerConverted = false
    private var seenBridges = 0
    @Volatile var running = false
        private set

    init {
        calib.onHaptic = { ok -> if (ok) haptics.reload() else haptics.error() }
        engine.screenLostListener = { reason ->
            lastEvent = "screen lost ($reason) — re-anchor"
            haptics.error()
            filter.reset()
        }
    }

    // ---------------------------------------------------------------- lifecycle
    /** The saved-PC entry for the current connection. */
    @Volatile var hostId: String? = null
        private set

    fun connect(p: Pairing) {
        link?.close()
        settings.pairing = p
        hostId = settings.hosts.upsert(p).id
        displayId = null; newScreen = false
        helloMisses = 0
        link = HostLink(p) { msg -> handleHost(msg) }.also { l ->
            // the PC got a new IP (router handed out another address): remember it
            l.onHostMoved = { ip ->
                val np = p.copy(host = ip)
                settings.pairing = np
                hostId?.let { id -> settings.hosts.update(id) { it.copy(uri = np.toUri()) } }
                lastEvent = "found the PC at its new address $ip"
            }
        }
        link?.hello(Build.MODEL)
    }

    private var helloMisses = 0
    private val linkTimer = Executors.newSingleThreadScheduledExecutor().also { ex ->
        ex.scheduleWithFixedDelay({ keepalive() }, 1, 1, TimeUnit.SECONDS)
    }

    /** Phone overheating: ARCore + camera throttle, tracking gets choppy. */
    @Volatile var hot = false
        private set

    fun disconnect() {
        link?.close(); link = null
    }

    /** Start tracking + streaming (Calibrate/Play screens). Camera permission must be granted. */
    fun start() {
        if (running) return
        running = true
        applySettings()
        wifiLock.acquire()  // avoid Wi-Fi power-save latency spikes (100+ ms) during play
        engine.start()
        val t = HandlerThread("sensors", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        sensorThread = t
        sensorHandler = Handler(t.looper)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        timers = Executors.newSingleThreadScheduledExecutor()   // keepalive now runs on linkTimer
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely(); sensorThread = null; sensorHandler = null
        timers?.shutdownNow(); timers = null
        engine.stop()
        filter.reset()
        buttons.set(0)
        if (wifiLock.isHeld) wifiLock.release()
    }

    fun applySettings() {
        val s = settings
        fuser.gain = s.fusionGain.toDouble()
        fuser.predictionMs = s.predictionMs.toDouble()
        filter.minCutoff = s.minCutoff.toDouble()
        filter.beta = s.beta.toDouble()
        barrel = s.barrel()
        sendPeriodNs = 1_000_000_000L / s.sendRateHz.coerceIn(30, 500)
        buttonMap = s.buttonMap
        offscreenReload = s.offscreenReload
        flick.enabled = s.flickReload
        flick.thresholdRadS = s.flickThreshold.toDouble()
        haptics.enabled = s.haptics
        haptics.configure(Haptics.Levels(
            clickStrength = s.clickStrength.toInt(), beatMs = s.beatMs.toLong(),
            recoilStrength = s.recoilStrength.toInt(), recoilMs = s.gunshotMs.toLong(),
            fadePercent = s.fadePercent.toInt(), reloadStrength = s.reloadStrength.toInt()))
    }

    // ---------------------------------------------------------------- buttons
    /** A mapped physical button changed state. */
    fun doAction(a: GunAction, down: Boolean) {
        when (a) {
            GunAction.RECENTER -> if (down) recenter()
            GunAction.OFF -> {}
            else -> setButton(a.bit, down)
        }
    }

    fun setButton(bit: Int, down: Boolean) {
        if (bit == Protocol.BTN_TRIGGER) return setTrigger(down)
        val prev = buttons.getAndUpdate { if (down) it or bit else it and bit.inv() }
        if (down && (prev and bit) == 0 && bit == Protocol.BTN_RELOAD) {
            reloads++; lastEvent = "reload (button)"
            haptics.reload()
            mainHandler.post { fx.reloads++ }
        }
    }

    private fun setTrigger(down: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (!down) {
            if (triggerConverted) triggerConverted = false
            else buttons.getAndUpdate { it and Protocol.BTN_TRIGGER.inv() }
            return
        }
        if ((buttons.get() and Protocol.BTN_TRIGGER) != 0 || triggerConverted) return  // already down
        if (trimming) {
            // centre-target shot: shift the screen so this hit becomes the centre (no shot in the game)
            triggerConverted = true
            if (recenter()) endTrim()
            return
        }
        if (calib.shooting) {
            // calibration: the shot pose comes from before the press; haptic is the confirmation
            buttons.getAndUpdate { it or Protocol.BTN_TRIGGER }
            calib.onTrigger(now)
            return
        }
        val a = aim
        if (offscreenReload && a.calibrated && !a.onScreen) {
            // shoot off-screen = reload (sent as the reload button; the host maps it per emulator)
            triggerConverted = true
            offscreenReloads++
            pulseReload("off-screen shot")
            return
        }
        buttons.getAndUpdate { it or Protocol.BTN_TRIGGER }
        shots++
        haptics.gunshot()
        mainHandler.post { fx.shots++ }
        flick.mute(now + (haptics.gunshotDurationMs + 80) * 1_000_000L)
    }

    /** Press reload for 80 ms (long enough for any emulator to sample a frame). */
    private fun pulseReload(source: String) {
        buttons.getAndUpdate { it or Protocol.BTN_RELOAD }
        reloads++; lastEvent = "reload ($source)"
        haptics.reload()
        mainHandler.post { fx.reloads++ }
        val release = Runnable { buttons.getAndUpdate { it and Protocol.BTN_RELOAD.inv() } }
        sensorHandler?.postDelayed(release, 80) ?: release.run()
    }

    /** Long-press recenter: aim at the screen centre, shift the screen so the hit becomes (0.5, 0.5). */
    fun recenter(): Boolean {
        val scr = engine.snapshot.screen ?: return false
        val (o, d) = lastRay ?: return false
        val hit = scr.intersect(o, d) ?: return false
        val h = o + d * hit.distance
        val delta = h - scr.center
        if (delta.norm() > 0.25 * scr.widthM) {
            // far off: almost certainly not aimed at the centre, or the anchor is wrong
            lastEvent = "recenter refused (off by ${"%.0f".format(delta.norm() * 100)} cm) — re-anchor instead"
            haptics.error()
            return false
        }
        engine.nudgeScreen(scr.u dot delta, scr.v dot delta)
        recenters++
        lastEvent = "recentered by ${"%.1f".format(delta.norm() * 100)} cm"
        haptics.reload()
        return true
    }

    // ---------------------------------------------------------------- calibration
    // Shown when ARCore loses track of the room partway through a flow A/Q sweep: observations
    // taken before and after that are in different, incompatible coordinate frames, which the
    // solver can only report much later as a confusing geometry-mismatch error - so catch it the
    // moment it happens instead and tell the player exactly what to do about it.
    private val trackingLostMessage = "Lost track of the room mid-scan, so the measurements no longer " +
        "line up. Turn on a light, avoid sweeping over blank walls/ceilings or mirrors, and move " +
        "slower this time - then try again."

    fun startCalibration(flow: String, fitT: Boolean = false) {
        if (flow == "Q") {  // quick lock: just look for the markers, no sweep coaching
            val startEpoch = engine.worldEpoch
            engine.cameraFrameListener = { f ->
                if (engine.worldEpoch != startEpoch) {
                    engine.cameraFrameListener = null
                    calib.abort("Q", trackingLostMessage)
                } else {
                    val omega = fuser.current(SystemClock.elapsedRealtimeNanos(), predict = false).omega.norm()
                    if (omega < ScanCoach.MAX_OMEGA) scanner.submit(f)
                }
            }
        }
        if (flow == "A") {
            coach.start()
            val startEpoch = engine.worldEpoch
            engine.cameraFrameListener = { f ->
                if (engine.worldEpoch != startEpoch) {
                    engine.cameraFrameListener = null
                    coach.stop()
                    calib.abort("A", trackingLostMessage)
                } else {
                    val omega = fuser.current(SystemClock.elapsedRealtimeNanos(), predict = false).omega.norm()
                    coach.onPose(f.tNs, f.q, f.p, omega)
                    // blurred / rolling-shutter-skewed frames poison the triangulation: skip them
                    if (omega < ScanCoach.MAX_OMEGA) scanner.submit(f)
                }
            }
        }
        calib.request(flow, barrel.toJson(), engine.sessionId, fitT)
    }

    fun cancelCalibration() {
        engine.cameraFrameListener = null
        coach.stop()
        calib.cancel()
    }

    fun setThumbnailListener(l: ((Bitmap) -> Unit)?) { engine.thumbnailListener = l }

    // ---------------------------------------------------------------- saved locations
    // A memory aid, not a real position fix: lets the player name a physical spot and keep a
    // picture of what the camera locked onto there, so they can match it up next time instead of
    // guessing where to stand. A printed marker stuck near the TV is the fool-proof fallback for
    // rooms that don't track well at all; this is for everyone else, to cut down on fumbling.
    fun saveLocation(name: String, thumb: Bitmap) {
        val bytes = java.io.ByteArrayOutputStream().apply { thumb.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        hostId?.let { hid -> settings.hosts.update(hid) { it.copy(locationName = name, locationThumbB64 = b64) } }
    }

    fun savedLocation(): Pair<String, Bitmap>? {
        val h = hostId?.let { settings.hosts.get(it) } ?: return null
        if (h.locationThumbB64.isEmpty()) return null
        val bmp = try {
            val bytes = Base64.decode(h.locationThumbB64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null } ?: return null
        return h.locationName to bmp
    }

    private fun handleHost(msg: JSONObject) {
        calib.onHostMessage(msg)
        when (msg.optString("type")) {
            "hello_ack" -> msg.optJSONObject("display")?.let { d ->
                val id = d.optString("id")
                displayLabel = "${d.optInt("w_px")}x${d.optInt("h_px")}"
                val changed = displayId != null && displayId != id         // switched while connected
                val saved = hostId?.let { settings.hosts.get(it) }
                val calibratedFor = saved?.calibratedDisplay
                val differs = calibratedFor != null && calibratedFor != id
                displayId = id
                hostId?.let { hid ->
                    settings.hosts.update(hid) {
                        it.copy(name = msg.optString("host_name").ifEmpty { it.name }, displayId = id,
                            displayLabel = displayLabel, sizeKnown = !msg.isNull("screen_size_m"),
                            lastUsed = System.currentTimeMillis())
                    }
                }
                if (changed || differs) {
                    newScreen = true
                    engine.clearScreen()      // the anchored rectangle is the OLD monitor
                    filter.reset()
                    lastEvent = "new screen ($displayLabel) - calibration needed"
                }
            }
            "calib_result" -> {
                engine.cameraFrameListener = null
                coach.stop()
                if (msg.optBoolean("ok")) {  // never apply a failed solve
                    msg.optJSONObject("screen")?.let {
                        engine.setScreen(ScreenRect.fromJson(it)); filter.reset()
                        settings.lastDisplayId = displayId; newScreen = false
                        val d = displayId
                        val flow = msg.optString("flow")
                        hostId?.let { hid -> settings.hosts.update(hid) {
                            it.copy(calibratedDisplay = d, sizeKnown = it.sizeKnown || flow == "A" || flow == "D") } }
                    }
                    msg.optJSONObject("barrel")?.takeIf { msg.optString("flow") != "C" }?.let {
                        val b = Barrel.fromJson(it)
                        if (b.t.norm() > 0.2) return@let  // implausible offset: ignore
                        settings.calibratedBarrel = b
                        settings.activeProfile = "calibrated"
                        barrel = b
                    }
                }
            }
            "calib_error" -> { engine.cameraFrameListener = null; coach.stop() }
            "emu_status" -> {
                val a = msg.optJSONArray("checks")
                val checks = (0 until (a?.length() ?: 0)).map { i ->
                    val c = a!!.getJSONObject(i)
                    EmuCheck(c.optBoolean("ok"), c.optString("text"), c.optString("fix"))
                }
                val lab = msg.optJSONObject("labels")
                val labels = lab?.keys()?.asSequence()?.associateWith { lab.optString(it) } ?: emptyMap()
                emuStatus = EmuStatus(msg.optString("emu"), msg.optString("game"), checks,
                    if (msg.isNull("cursor_ok")) null else msg.optBoolean("cursor_ok"), System.currentTimeMillis(), labels)
            }
            "emu_fix_result" -> emuFixResult = msg.optString("message")
            "input_echo" -> inputEcho = Triple(msg.optString("button"), msg.optString("sent"), System.currentTimeMillis())
        }
    }

    // ---------------------------------------------------------------- hot path (sensor thread)
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        fuser.onGyro(e.timestamp, e.values[0], e.values[1], e.values[2])
        val now = SystemClock.elapsedRealtimeNanos()
        if (calib.active) {
            calib.onSample(fuser.current(now, predict = false))
        } else {
            val w = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                e.values[2] * e.values[2]).toDouble())
            if (flick.update(e.timestamp, w) && aim.calibrated) { flicks++; pulseReload("flick") }
        }
        // Gyro events arrive with jitter; a strict "period elapsed" test skips every other
        // early event and the rate collapses (seen: 158 Hz for a 200 Hz target). Keep a
        // schedule instead and allow events up to 25% early.
        if (now - lastSendNs < sendPeriodNs * 3 / 4) return
        lastSendNs = if (now - lastSendNs > 2 * sendPeriodNs) now else lastSendNs + sendPeriodNs
        sendAim(now)
    }

    private var lastSentButtons = 0
    private var triggerPresses = 0
    private var reloadPresses = 0

    private fun sendAim(now: Long) {
        val snap = engine.snapshot
        val f = fuser.current(now, predict = true)
        var flags = 0
        var x = aim.x; var y = aim.y; var on = false
        val scr = snap.screen
        if (f.valid) {
            val (o, dRaw) = barrel.ray(f.q, f.p)
            val d = filter.filter(now, dRaw)
            lastRay = Pair(o, d)
            if (scr != null) {
                val hit = scr.intersect(o, d)
                if (hit != null) {
                    x = hit.x.toFloat().coerceIn(0f, 1f)
                    y = hit.y.toFloat().coerceIn(0f, 1f)
                    on = hit.onScreen
                }
                flags = flags or Protocol.FLAG_CALIBRATED
            }
        }
        if (on) flags = flags or Protocol.FLAG_ON_SCREEN
        if (snap.tracking) flags = flags or Protocol.FLAG_TRACKING
        if (f.gyroOnly) flags = flags or Protocol.FLAG_GYRO_ONLY
        val b = buttons.get()
        if ((b and Protocol.BTN_TRIGGER) != 0 && (lastSentButtons and Protocol.BTN_TRIGGER) == 0) triggerPresses++
        if ((b and Protocol.BTN_RELOAD) != 0 && (lastSentButtons and Protocol.BTN_RELOAD) == 0) reloadPresses++
        lastSentButtons = b
        link?.sendAim(now, x, y, flags, b, (triggerPresses and 15) or ((reloadPresses and 15) shl 4))

        sentInWindow++
        if (now - windowStart >= 500_000_000L) {
            rate = sentInWindow * 1e9 / (now - windowStart)
            windowStart = now; sentInWindow = 0
        }
        if (engine.bridges != seenBridges) {
            seenBridges = engine.bridges
            lastEvent = "tracking back - screen carried over (hold centre to recenter if off)"
            haptics.reload()
        }
        val l = link
        aim = AimState(x, y, on, snap.tracking, f.gyroOnly, scr != null, snap.failure, rate, snap.fps,
            snap.featureCount, l?.rttMs ?: -1.0, l?.connected == true, b, aim.battery, markersSeen,
            reloads, shots, flicks, offscreenReloads, recenters, lastEvent, engine.anchorJumps,
            l?.netLoss ?: 0.0, l?.netLateMs ?: 0.0, hot)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun keepalive() {
        val l = link ?: return
        try {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batt = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            aim = aim.copy(battery = batt)
            if (Build.VERSION.SDK_INT >= 29) {
                val pm = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                hot = pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE
            }
            calib.tick()
            if (!l.connected) {
                l.hello(Build.MODEL)
                // no answer for a few seconds: the PC may have a new IP - ask the whole network
                if (++helloMisses >= 3) l.helloBroadcast(Build.MODEL)
            } else helloMisses = 0
            l.ping(JSONObject().put("rate_hz", rate).put("tracking", aim.tracking)
                .put("gyro_only", aim.gyroOnly).put("battery", batt).put("reloads", reloads)
                .put("anchor_jumps", engine.anchorJumps))
        } catch (e: Exception) {
            Log.w("GunController", "keepalive: $e")
        }
    }

    companion object {
        @Volatile private var instance: GunController? = null
        fun get(ctx: Context): GunController = instance ?: synchronized(this) {
            instance ?: GunController(ctx.applicationContext).also { instance = it }
        }
    }
}
