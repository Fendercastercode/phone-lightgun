package dev.lightgun.fusion

import kotlin.math.PI

/**
 * 1-euro filter on a unit direction vector. Speed (rad/s) is scaled by [pxPerRad] so
 * the classic pixel-unit defaults (minCutoff 1 Hz, beta 0.007) behave as intended.
 */
class OneEuroVec(
    @Volatile var minCutoff: Double = 1.0,
    @Volatile var beta: Double = 0.007,
    @Volatile var dCutoff: Double = 1.0,
    @Volatile var pxPerRad: Double = 3800.0,
) {
    private var prev: Vec3? = null
    private var prevRaw: Vec3 = Vec3.ZERO
    private var prevT = 0L
    private var dx = 0.0

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun reset() { prev = null }

    fun filter(tNs: Long, v: Vec3): Vec3 {
        val p = prev
        if (p == null) {
            prev = v; prevRaw = v; prevT = tNs; dx = 0.0
            return v
        }
        if (tNs <= prevT) return p
        val dt = (tNs - prevT) * 1e-9
        prevT = tNs
        val speed = (v - prevRaw).norm() / dt * pxPerRad
        prevRaw = v
        dx += alpha(dCutoff, dt) * (speed - dx)
        val cutoff = minCutoff + beta * dx
        val a = alpha(cutoff, dt)
        val out = (p + (v - p) * a).normalized()
        prev = out
        return out
    }
}
