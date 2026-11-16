package dev.lightgun.input

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Gunshot = the strongest pulse the motor can do (full amplitude, configurable length,
 * short recoil tail). Reload = a short crisp click. Uses the MEDIA/GAME usage so the
 * system "touch feedback" toggle doesn't mute it.
 */
class Haptics(context: Context) {
    private val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    private val amp = vib.hasAmplitudeControl()
    /** All tunables in one place; rebuilt into effects by [configure]. Strengths are 1..255. */
    data class Levels(
        val clickStrength: Int = 255,
        val beatMs: Long = 28,
        val recoilStrength: Int = 255,
        val recoilMs: Long = 55,
        val fadePercent: Int = 100,   // fade length as % of recoilMs
        val reloadStrength: Int = 0,  // 0 = the phone's built-in crisp click
    )

    @Volatile var levels = Levels()
        private set
    @Volatile var enabled = true
    private var gunshot = buildGunshot(levels)
    private var reload = buildReload(levels)

    fun configure(l: Levels) {
        levels = l
        gunshot = buildGunshot(l)
        reload = buildReload(l)
    }

    private fun buildReload(l: Levels): VibrationEffect =
        if (l.reloadStrength <= 0 && Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        else VibrationEffect.createOneShot(12, if (amp) l.reloadStrength.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE)
    private val error: VibrationEffect = VibrationEffect.createWaveform(
        longArrayOf(0, 40, 60, 40), if (amp) intArrayOf(0, 120, 0, 120) else intArrayOf(0, 255, 0, 255), -1)

    // Gunshot = sharp trigger CLICK, a short beat, then a HEAVY recoil kick that dies away.
    private fun pattern(l: Levels): Pair<LongArray, IntArray> {
        val fade = (l.recoilMs * l.fadePercent / 100).coerceAtLeast(0)
        val r = l.recoilStrength.coerceIn(1, 255)
        return Pair(
            longArrayOf(0, CLICK_MS, l.beatMs.coerceAtLeast(0), l.recoilMs, fade / 2, fade / 2),
            intArrayOf(0, l.clickStrength.coerceIn(1, 255), 0, r, r * 2 / 3, r / 4))
    }

    private fun buildGunshot(l: Levels): VibrationEffect = if (amp) {
        val (t, a) = pattern(l)
        VibrationEffect.createWaveform(t, a, -1)
    } else {
        // on/off motors: click, beat, long buzz (no amplitude shaping available)
        VibrationEffect.createWaveform(longArrayOf(0, CLICK_MS, l.beatMs, l.recoilMs + l.recoilMs * l.fadePercent / 100), -1)
    }

    /** Total duration of the gunshot pattern (used to mute motion detectors meanwhile). */
    val gunshotDurationMs get() = pattern(levels).first.sum()
    /** False on motors that can only buzz on/off (strength sliders then do nothing). */
    val hasStrengthControl get() = amp

    fun gunshot() = play(gunshot)

    private companion object {
        const val CLICK_MS = 9L   // crisp trigger break
    }
    fun reload() = play(reload)
    fun error() = play(error)

    private fun play(e: VibrationEffect) {
        if (!enabled || !vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 33) {
            vib.vibrate(e, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(e, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
        }
    }
}
