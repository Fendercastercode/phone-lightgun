package dev.lightgun.calib

import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3

/**
 * Live guidance for flow A (screen scan). The host needs views from different POSITIONS
 * (sliding the phone), not just different angles (tilting), with all 5 markers in view and
 * the phone moving slowly enough that frames aren't blurred.
 *
 * Coordinates: x = sideways (right +), y = height (up +), relative to where the scan started,
 * in metres, using the horizontal direction the camera faced at the start.
 */
class ScanCoach {
    enum class Hint { FIND_TV, STEP_BACK, MOVE_CLOSER, SHOW_ALL, TOO_FAST, LEFT, RIGHT, UP, DOWN, KEEP_GOING, TILTING }

    data class State(
        val active: Boolean = false,
        val hint: Hint = Hint.FIND_TV,
        val markerIds: Set<Int> = emptySet(),
        val markerSizeFrac: Float = 0f,     // mean marker side / image width
        val moving: Boolean = false,
        val x: Float = 0f, val y: Float = 0f,
        val trail: List<Pair<Float, Float>> = emptyList(),
        val left: Boolean = false, val right: Boolean = false, val up: Boolean = false, val down: Boolean = false,
        val usedFrames: Int = 0,
    )

    @Volatile var state = State()
        private set

    private var origin: Vec3? = null
    private var rightAxis = Vec3(1.0, 0.0, 0.0)
    private var startQ: Quat? = null
    private val trail = ArrayList<Pair<Float, Float>>()
    private var lastTrailT = 0L
    private var tiltOnlySince = 0L

    fun start() {
        origin = null; startQ = null; trail.clear(); tiltOnlySince = 0L
        state = State(active = true)
    }

    fun stop() { state = state.copy(active = false) }

    /** Every camera frame (AR thread). [q],[p] = ARCore camera pose; [omega] = gyro rad/s. */
    fun onPose(tNs: Long, q: Quat, p: Vec3, omega: Double) {
        if (!state.active) return
        val o = origin ?: run {
            origin = p; startQ = q
            // camera looks down -Z; sideways axis = horizontal perpendicular to that
            val fwd = q.rotate(Vec3(0.0, 0.0, -1.0)).let { Vec3(it.x, 0.0, it.z) }.normalized()
            rightAxis = Vec3(-fwd.z, 0.0, fwd.x)
            p
        }
        val d = p - o
        val x = (d dot rightAxis).toFloat()
        val y = d.y.toFloat()
        if (tNs - lastTrailT > 100_000_000L) {
            lastTrailT = tNs
            trail.add(x to y)
            if (trail.size > 120) trail.removeAt(0)
        }
        // rotating a lot without translating = tilting in place (gives the host no parallax)
        val turned = startQ?.let { Math.toDegrees(Quat.angle(it, q)) } ?: 0.0
        val moved = d.norm()
        if (turned > 20 && moved < 0.06) { if (tiltOnlySince == 0L) tiltOnlySince = tNs } else tiltOnlySince = 0L

        val s = state
        val left = s.left || x < -REACH_X
        val right = s.right || x > REACH_X
        val up = s.up || y > REACH_Y
        val down = s.down || y < -REACH_Y
        val moving = omega > MAX_OMEGA
        state = s.copy(x = x, y = y, trail = ArrayList(trail), left = left, right = right, up = up, down = down,
            moving = moving, hint = hint(s.markerIds.size, s.markerSizeFrac, moving, left, right, up, down,
                x, y, tiltOnlySince != 0L && tNs - tiltOnlySince > 700_000_000L))
    }

    /** After each detection (scanner thread). */
    fun onDetections(ids: Set<Int>, sideFrac: Float, used: Boolean) {
        if (!state.active) return
        val s = state
        state = s.copy(markerIds = ids, markerSizeFrac = sideFrac, usedFrames = s.usedFrames + if (used) 1 else 0,
            hint = hint(ids.size, sideFrac, s.moving, s.left, s.right, s.up, s.down, s.x, s.y, s.hint == Hint.TILTING))
    }

    private fun hint(n: Int, size: Float, moving: Boolean, l: Boolean, r: Boolean, u: Boolean, d: Boolean,
                     x: Float, y: Float, tilting: Boolean): Hint = when {
        n == 0 -> Hint.FIND_TV
        n < 5 && size > 0.09f -> Hint.STEP_BACK          // big markers but some cut off: too close
        n < 5 -> Hint.SHOW_ALL
        size < 0.035f -> Hint.MOVE_CLOSER                  // tiny markers: corners get imprecise
        moving -> Hint.TOO_FAST
        tilting -> Hint.TILTING
        // go to whichever side isn't done yet, starting with the one we're not already on
        !l && (r || x <= 0f) -> Hint.LEFT
        !r -> Hint.RIGHT
        !u && (d || y <= 0f) -> Hint.UP
        !d -> Hint.DOWN
        else -> Hint.KEEP_GOING
    }

    companion object {
        const val REACH_X = 0.15f   // metres each way sideways
        const val REACH_Y = 0.10f   // metres up/down
        const val MAX_OMEGA = 0.6   // rad/s; faster frames are blurred and skipped
    }
}
