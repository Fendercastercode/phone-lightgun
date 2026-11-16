package dev.lightgun

import dev.lightgun.calib.ScanCoach
import dev.lightgun.calib.ScanCoach.Hint
import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanCoachTest {
    private val all = setOf(0, 1, 2, 3, 4)

    @Test fun guidesLeftRightUpDownThenDone() {
        val c = ScanCoach(); c.start()
        var t = 0L
        fun at(x: Double, y: Double, q: Quat = Quat.IDENTITY) {
            t += 150_000_000L
            c.onPose(t, q, Vec3(x, y, 0.0), 0.1); c.onDetections(all, 0.06f, true)
        }
        at(0.0, 0.0); assertEquals(Hint.LEFT, c.state.hint)
        at(-0.2, 0.0); assertEquals(Hint.RIGHT, c.state.hint)
        at(0.2, 0.0); assertEquals(Hint.UP, c.state.hint)
        at(0.0, 0.15); assertEquals(Hint.DOWN, c.state.hint)
        at(0.0, -0.15); assertEquals(Hint.KEEP_GOING, c.state.hint)
    }

    @Test fun framingAndSpeedComeFirst() {
        val c = ScanCoach(); c.start()
        c.onPose(1, Quat.IDENTITY, Vec3.ZERO, 0.1)
        c.onDetections(emptySet(), 0f, false); assertEquals(Hint.FIND_TV, c.state.hint)
        c.onDetections(setOf(4, 0), 0.12f, true); assertEquals(Hint.STEP_BACK, c.state.hint)
        c.onDetections(all, 0.02f, true); assertEquals(Hint.MOVE_CLOSER, c.state.hint)
        c.onPose(2, Quat.IDENTITY, Vec3.ZERO, 2.0); c.onDetections(all, 0.06f, true)
        assertEquals(Hint.TOO_FAST, c.state.hint)
    }

    @Test fun tiltingInPlaceIsCalledOut() {
        val c = ScanCoach(); c.start()
        c.onPose(0, Quat.IDENTITY, Vec3.ZERO, 0.1); c.onDetections(all, 0.06f, true)
        val turned = Quat.fromRotVec(Vec3(0.0, 0.6, 0.0))  // ~34 degrees, no translation
        c.onPose(100_000_000L, turned, Vec3.ZERO, 0.1)
        c.onPose(1_000_000_000L, turned, Vec3.ZERO, 0.1)
        assertEquals(Hint.TILTING, c.state.hint)
    }
}
