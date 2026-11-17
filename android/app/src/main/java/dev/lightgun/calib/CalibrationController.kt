package dev.lightgun.calib

import dev.lightgun.fusion.PoseFuser
import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Phone side of the calibration flows: captures steady shots and tracks host state. */
class CalibrationController(private val send: (JSONObject) -> Unit) {
    enum class Phase { IDLE, WAITING, SHOOTING, SCANNING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val flow: String = "",
        val index: Int = 0,
        val total: Int = 0,
        val targetX: Double = 0.5,
        val targetY: Double = 0.5,
        val progress: Double = 0.0,
        val message: String = "",
        val capturing: Boolean = false,
    )

    @Volatile var state = State()
        private set
    /** Increments on every calib_result; lets the tutorial notice "a new result arrived". */
    @Volatile var resultSeq = 0
        private set
    @Volatile var lastResultFlow = ""
        private set
    @Volatile var lastResultOk = false
        private set
    var onStateChanged: (State) -> Unit = {}
    var onHaptic: (ok: Boolean) -> Unit = {}
    val shooting get() = state.phase == Phase.SHOOTING

    private fun set(s: State) { state = s; onStateChanged(s) }

    val active get() = state.phase == Phase.WAITING || state.phase == Phase.SHOOTING || state.phase == Phase.SCANNING

    // A lost calib_request (UDP) would leave us "Waiting for PC..." forever: resend until the PC answers.
    private var pendingRequest: JSONObject? = null
    private var requestSentMs = 0L
    private var requestTries = 0

    /** Called about once a second. */
    fun tick() {
        val req = pendingRequest ?: return
        if (!active) { pendingRequest = null; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - requestSentMs < 2000) return
        if (requestTries >= 4) {
            pendingRequest = null
            set(State(Phase.FAILED, state.flow, message = "The PC didn't answer - is RetroSight running on it?"))
            return
        }
        requestTries++; requestSentMs = now
        send(req)
    }

    fun request(flow: String, barrel: JSONObject, arSession: String, fitT: Boolean = false) {
        val req = JSONObject().put("type", "calib_request").put("flow", flow).put("barrel", barrel)
            .put("fit_t", fitT).put("ar_session", arSession)
        pendingRequest = req; requestSentMs = android.os.SystemClock.elapsedRealtime(); requestTries = 0
        send(req)
        set(State(if (flow == "A" || flow == "Q") Phase.SCANNING else Phase.WAITING, flow,
            message = when (flow) {
                "A" -> "Hold the phone out of the grip, camera at the TV, sweep slowly"
                "Q" -> "Point the BACK CAMERA at the TV (phone sideways, screen facing you)"
                else -> "Waiting for PC..."
            }))
    }

    fun cancel() {
        pendingRequest = null
        send(JSONObject().put("type", "calib_cancel"))
        set(State(message = "Cancelled"))
    }

    /**
     * Like [cancel], but for a specific, explained reason (e.g. the camera lost track of the
     * room mid-scan) rather than the player's own choice: tells the host to drop its in-progress
     * session, and leaves the player on a FAILED state with instructions instead of silently
     * restarting or letting bad data reach the solver, where it only surfaces later as a
     * confusing geometry-mismatch error.
     */
    fun abort(flow: String, message: String) {
        pendingRequest = null
        send(JSONObject().put("type", "calib_cancel"))
        set(State(Phase.FAILED, flow, message = message))
    }

    /** Host messages (any thread). Returns true if consumed. */
    fun onHostMessage(msg: JSONObject): Boolean {
        if (msg.optString("type").startsWith("calib")) {
            android.util.Log.i("Calib", "host: $msg")
            pendingRequest = null   // the PC heard us
        }
        when (msg.optString("type")) {
            "calib_target" -> set(state.copy(phase = Phase.SHOOTING, flow = msg.optString("flow"),
                index = msg.optInt("index"), total = msg.optInt("total"),
                targetX = msg.optDouble("x"), targetY = msg.optDouble("y"),
                message = if (msg.optBoolean("retry")) "Shot not received - fire again" else
                    "Aim at target ${msg.optInt("index") + 1}/${msg.optInt("total")} and fire"))
            "calib_progress" -> set(state.copy(phase = Phase.SCANNING, progress = msg.optDouble("fraction"),
                message = if (msg.optString("flow") == "Q") "Point the back camera at the TV - ${msg.optString("message")}"
                    else "${msg.optInt("views")} views - ${msg.optString("message")}"))
            "calib_result" -> {
                lastResultFlow = msg.optString("flow"); lastResultOk = msg.optBoolean("ok"); resultSeq++
                set(State(if (lastResultOk) Phase.DONE else Phase.FAILED, lastResultFlow, progress = 1.0,
                    message = msg.optString("message")))
            }
            "calib_error" -> set(State(Phase.FAILED, msg.optString("flow"), message = msg.optString("message")))
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- steady-shot capture
    // The shot pose is taken from the window BEFORE the trigger press: pressing a volume
    // key or touch zone jerks the phone, and the confirmation haptic shakes it afterwards.
    // If the player was not steady beforehand we fall back to a window after the press
    // (starting once the jerk has settled).
    private val lock = Any()
    private val history = ArrayDeque<PoseFuser.Fused>()
    private var postStart = 0L
    private val post = ArrayList<PoseFuser.Fused>()

    private fun steady(f: PoseFuser.Fused) = f.valid && !f.gyroOnly && f.omega.norm() < MAX_OMEGA

    fun onTrigger(nowNs: Long) {
        if (state.phase != Phase.SHOOTING || state.capturing) return
        val pre = synchronized(lock) {
            history.filter { it.tNs in (nowNs - PRE_FROM_NS)..(nowNs - PRE_TO_NS) }
        }
        val good = pre.filter { steady(it) }
        if (pre.isNotEmpty() && good.size >= MIN_SAMPLES && good.size >= pre.size * 0.8) {
            finishShot(good)
            return
        }
        synchronized(lock) { post.clear(); postStart = nowNs + POST_DELAY_NS }
        set(state.copy(capturing = true, message = "Hold still…"))
    }

    /** Fed from the sensor thread with unpredicted fused poses while a flow is active. */
    fun onSample(f: PoseFuser.Fused) {
        var done: List<PoseFuser.Fused>? = null
        synchronized(lock) {
            history.addLast(f)
            while (history.isNotEmpty() && f.tNs - history.first().tNs > HISTORY_NS) history.removeFirst()
            if (postStart != 0L && f.tNs >= postStart) {
                if (steady(f)) post.add(f)
                if (f.tNs - postStart >= WINDOW_NS) {
                    done = ArrayList(post)
                    postStart = 0L
                }
            }
        }
        done?.let { finishShot(it) }
    }

    private fun finishShot(s: List<PoseFuser.Fused>) {
        if (s.size < MIN_SAMPLES) {
            onHaptic(false)
            set(state.copy(capturing = false, message = "Too shaky (${s.size} steady samples) - hold still and fire again"))
            return
        }
        val q0 = s[0].q
        var ax = 0.0; var ay = 0.0; var az = 0.0; var aw = 0.0
        var p = Vec3.ZERO
        for (f in s) {
            val q = if (f.q.dot(q0) < 0) f.q.neg() else f.q
            ax += q.x; ay += q.y; az += q.z; aw += q.w
            p += f.p
        }
        val qm = Quat(ax, ay, az, aw).normalized()
        p = p * (1.0 / s.size)
        val wRms = sqrt(s.sumOf { it.omega.norm().let { w -> w * w } } / s.size)
        send(JSONObject().put("type", "shot").put("index", state.index)
            .put("q", JSONArray(qm.toList())).put("p", JSONArray(p.toList()))
            .put("n", s.size).put("w_rms", wRms))
        onHaptic(true)
        set(state.copy(capturing = false, message = "Shot ${state.index + 1} sent"))
    }

    companion object {
        const val PRE_FROM_NS = 230_000_000L   // pre-trigger window: [t-230ms, t-50ms]
        const val PRE_TO_NS = 50_000_000L
        const val POST_DELAY_NS = 200_000_000L
        const val HISTORY_NS = 400_000_000L
        const val WINDOW_NS = 150_000_000L
        const val MAX_OMEGA = 0.35   // rad/s
        const val MIN_SAMPLES = 10
    }
}
