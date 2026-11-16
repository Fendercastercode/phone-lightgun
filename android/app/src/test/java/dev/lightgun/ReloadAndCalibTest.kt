package dev.lightgun

import dev.lightgun.calib.CalibrationController
import dev.lightgun.fusion.PoseFuser
import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import dev.lightgun.input.FlickDetector
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReloadAndCalibTest {
    private fun run(d: FlickDetector, profile: (Double) -> Double, ms: Int, t0: Long = 1_000_000_000L): Int {
        var n = 0
        for (i in 0 until ms * 2) {  // 500 Hz
            val t = t0 + i * 2_000_000L
            if (d.update(t, profile(i * 2.0))) n++
        }
        return n
    }

    @Test fun flickDetectedOnce() {
        // 80 ms burst at 14 rad/s, then the return swing (another burst), then calm
        val n = run(FlickDetector(), { ms ->
            when { ms < 100 -> 0.3; ms < 180 -> 14.0; ms < 200 -> 1.0; ms < 260 -> 12.0; else -> 0.2 }
        }, 1000)
        assertEquals(1, n)
    }

    @Test fun slowSwingIsNotAFlick() {
        // aiming across the screen fast but sustained (400 ms above threshold)
        assertEquals(0, run(FlickDetector(), { ms -> if (ms in 100.0..500.0) 10.0 else 0.2 }, 1000))
        // normal aiming speeds never trigger
        assertEquals(0, run(FlickDetector(), { ms -> 3.0 + 2.0 * Math.sin(ms / 50) }, 2000))
    }

    @Test fun mutedDuringHaptics() {
        val d = FlickDetector()
        d.mute(1_000_000_000L + 300_000_000L)
        assertEquals(0, run(d, { ms -> if (ms in 100.0..160.0) 15.0 else 0.0 }, 300))
    }

    private fun fused(t: Long, q: Quat, w: Double) =
        PoseFuser.Fused(t, q, Vec3(0.1, 0.2, 0.3), Vec3(0.0, w, 0.0), gyroOnly = false, valid = true)

    @Test fun calibrationShotUsesPreTriggerPose() {
        val sent = ArrayList<JSONObject>()
        val c = CalibrationController { sent.add(it) }
        c.onHostMessage(JSONObject("""{"type":"calib_target","flow":"C","index":0,"total":4,"x":0.15,"y":0.15}"""))
        val steadyQ = Quat.fromRotVec(Vec3(0.0, 0.2, 0.0))
        var t = 1_000_000_000L
        repeat(150) { t += 2_000_000L; c.onSample(fused(t, steadyQ, 0.02)) }   // 300 ms steady
        c.onTrigger(t)
        // the press jerks the phone afterwards; must not matter
        repeat(50) { t += 2_000_000L; c.onSample(fused(t, Quat.fromRotVec(Vec3(0.0, 0.5, 0.0)), 3.0)) }
        val shot = sent.last { it.getString("type") == "shot" }
        val q = shot.getJSONArray("q")
        val got = Quat(q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3))
        assertTrue(Quat.angle(got, steadyQ) < 1e-6)
        assertEquals(0, shot.getInt("index"))
    }

    @Test fun shakyBeforeTriggerFallsBackToAfter() {
        val sent = ArrayList<JSONObject>()
        val c = CalibrationController { sent.add(it) }
        c.onHostMessage(JSONObject("""{"type":"calib_target","flow":"C","index":1,"total":4,"x":0.85,"y":0.15}"""))
        var t = 1_000_000_000L
        repeat(150) { t += 2_000_000L; c.onSample(fused(t, Quat.IDENTITY, 2.0)) }  // moving
        c.onTrigger(t)
        assertFalse(sent.any { it.getString("type") == "shot" })
        assertTrue(c.state.capturing)
        val q2 = Quat.fromRotVec(Vec3(0.1, 0.0, 0.0))
        repeat(250) { t += 2_000_000L; c.onSample(fused(t, q2, 0.05)) }  // settles
        val shot = sent.last { it.getString("type") == "shot" }
        assertEquals(1, shot.getInt("index"))
    }
}
