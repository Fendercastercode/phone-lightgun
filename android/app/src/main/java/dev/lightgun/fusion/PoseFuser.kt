package dev.lightgun.fusion

/**
 * Complementary filter: gyro integrated at sensor rate, pulled toward ARCore orientation
 * at camera rate. Position is ARCore-only with short linear extrapolation.
 * All timestamps are elapsedRealtimeNanos. Thread-safe (coarse lock; calls are tiny).
 */
class PoseFuser(
    @Volatile var gain: Double = 0.1,
    @Volatile var predictionMs: Double = 16.0,
) {
    data class Fused(
        val tNs: Long,
        val q: Quat,          // R_WP (Android sensor frame -> ARCore world)
        val p: Vec3,
        val omega: Vec3,      // body angular velocity, rad/s
        val gyroOnly: Boolean,
        val valid: Boolean,
    )

    private val lock = Any()

    // gyro integration in an arbitrary frame G
    private var qG = Quat.IDENTITY
    private var lastGyroT = 0L
    private var omega = Vec3.ZERO
    private val histT = LongArray(HIST)
    private val histQ = arrayOfNulls<Quat>(HIST)
    private var histHead = 0
    private var histCount = 0

    // ARCore correction
    private var qOff: Quat? = null
    private var pLast = Vec3.ZERO
    private var tLast = 0L
    private var vel = Vec3.ZERO
    private var lost = true
    private var lostSince = 0L
    var gyroOnly = false
        private set

    fun onGyro(tNs: Long, wx: Float, wy: Float, wz: Float) = synchronized(lock) {
        val w = Vec3(wx.toDouble(), wy.toDouble(), wz.toDouble())
        if (lastGyroT != 0L) {
            val dt = (tNs - lastGyroT) * 1e-9
            if (dt > 0 && dt < 0.05) {
                val wMid = (omega + w) * 0.5        // trapezoid
                qG = (qG * Quat.fromRotVec(wMid * dt)).normalized()
            }
        }
        omega = w
        lastGyroT = tNs
        histT[histHead] = tNs
        histQ[histHead] = qG
        histHead = (histHead + 1) % HIST
        if (histCount < HIST) histCount++
    }

    /** ARCore frame. [tracking] false => hold last pose and run gyro-only. */
    fun onArFrame(tNs: Long, q: Quat, p: Vec3, tracking: Boolean) = synchronized(lock) {
        if (!tracking) {
            if (qOff != null) gyroOnly = true
            if (!lost) lostSince = tNs
            lost = true
            return
        }
        val qAt = gyroAt(tNs) ?: qG
        var err = q * qAt.conj()
        val off = qOff
        // Short dropouts (e.g. motion blur during a reload flick): the gyro carried us
        // through, so re-converge quickly but smoothly instead of a visible snap.
        // Long dropouts: snap, the gyro estimate may have drifted.
        val shortLoss = lost && off != null && tNs - lostSince < SHORT_LOSS_NS
        qOff = if (off == null || (lost && !shortLoss)) err else {
            if (off.dot(err) < 0) err = err.neg()
            Quat.slerp(off, err, if (shortLoss) maxOf(gain, 0.35) else gain).normalized()
        }
        if (tLast != 0L && !lost) {
            val dt = (tNs - tLast) * 1e-9
            if (dt > 1e-4 && dt < 0.2) {
                val vNew = (p - pLast) * (1.0 / dt)
                vel = vel + (vNew - vel) * 0.3
            }
        } else vel = Vec3.ZERO
        pLast = p
        tLast = tNs
        lost = false
        gyroOnly = false
    }

    private fun gyroAt(t: Long): Quat? {
        if (histCount == 0) return null
        // newest first; find bracket
        var newer: Int = -1
        for (k in 0 until histCount) {
            val i = (histHead - 1 - k + HIST) % HIST
            if (histT[i] <= t) {
                if (newer < 0) return histQ[i]
                val t0 = histT[i]; val t1 = histT[newer]
                val a = if (t1 > t0) (t - t0).toDouble() / (t1 - t0) else 0.0
                return Quat.slerp(histQ[i]!!, histQ[newer]!!, a)
            }
            newer = i
        }
        return histQ[(histHead - histCount + HIST) % HIST]
    }

    /** Current fused pose; with [predict] adds the configured look-ahead. */
    fun current(nowNs: Long, predict: Boolean): Fused = synchronized(lock) {
        val off = qOff ?: return Fused(nowNs, Quat.IDENTITY, Vec3.ZERO, omega, false, false)
        var q = off * qG
        if (predict) {
            val ahead = ((nowNs - lastGyroT).coerceIn(0, 50_000_000L)) * 1e-9 + predictionMs * 1e-3
            q = q * Quat.fromRotVec(omega * ahead)
        }
        val ext = if (gyroOnly) 0.0 else ((nowNs - tLast).coerceIn(0, 50_000_000L)) * 1e-9 +
            (if (predict) predictionMs * 1e-3 else 0.0)
        Fused(nowNs, q.normalized(), pLast + vel * ext, omega, gyroOnly, true)
    }

    fun reset() = synchronized(lock) {
        qOff = null; lost = true; gyroOnly = false; tLast = 0L; vel = Vec3.ZERO
    }

    companion object {
        private const val HIST = 512
        private const val SHORT_LOSS_NS = 1_000_000_000L
    }
}
