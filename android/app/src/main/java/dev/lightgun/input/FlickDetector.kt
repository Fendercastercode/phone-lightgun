package dev.lightgun.input

/**
 * Detects a quick wrist flick (sharp, short burst of angular velocity) for reload.
 *
 * A flick is |ω| above [thresholdRadS] for between [minMs] and [maxMs], followed by a
 * cooldown. Sustained fast swings (aiming across the screen) last longer than [maxMs]
 * and are ignored. Haptic pulses shake the gyro a little, so callers [mute] the detector
 * while the motor runs.
 */
class FlickDetector(
    @Volatile var thresholdRadS: Double = 9.0,
    @Volatile var minMs: Double = 12.0,
    @Volatile var maxMs: Double = 220.0,
    @Volatile var cooldownMs: Double = 450.0,
) {
    @Volatile var enabled = true
    private var burstStart = 0L
    private var peak = 0.0
    private var cooldownUntil = 0L
    private var mutedUntil = 0L

    fun mute(untilNs: Long) { if (untilNs > mutedUntil) mutedUntil = untilNs }

    /** Feed every gyro sample; returns true exactly once per detected flick. */
    fun update(tNs: Long, omegaNorm: Double): Boolean {
        if (!enabled || tNs < cooldownUntil || tNs < mutedUntil) {
            burstStart = 0L
            return false
        }
        if (omegaNorm >= thresholdRadS) {
            if (burstStart == 0L) { burstStart = tNs; peak = 0.0 }
            if (omegaNorm > peak) peak = omegaNorm
            if ((tNs - burstStart) / 1e6 > maxMs) burstStart = -1L // too long: a swing, not a flick
            return false
        }
        if (burstStart == -1L) {
            // swing ended; require calm before arming again
            burstStart = 0L
            cooldownUntil = tNs + (cooldownMs * 0.5e6).toLong()
            return false
        }
        if (burstStart > 0L) {
            val dur = (tNs - burstStart) / 1e6
            burstStart = 0L
            if (dur >= minMs && dur <= maxMs) {
                cooldownUntil = tNs + (cooldownMs * 1e6).toLong()
                return true
            }
        }
        return false
    }
}
