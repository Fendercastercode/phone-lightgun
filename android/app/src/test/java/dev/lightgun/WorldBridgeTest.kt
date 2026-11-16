package dev.lightgun

import dev.lightgun.aim.Barrel
import dev.lightgun.aim.ScreenRect
import dev.lightgun.aim.WorldBridge
import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldBridgeTest {
    @Test fun aimSurvivesWorldReset() {
        // old world: screen 2.2 m ahead; phone aiming at its centre
        val u = Vec3(1.0, 0.0, 0.0); val v = Vec3(0.0, -1.0, 0.0)
        val screenOld = ScreenRect(Vec3(-0.6, 0.6, -2.2), u, v, 1.2, 0.675)
        val barrel = Barrel.CAMERA_AXIS
        val qPhoneOld = Quat.fromRotVec(Vec3(0.05, 0.2, 0.02))
        val pPhoneOld = Vec3(0.1, 0.0, 0.0)
        // ARCore restarts in a new frame: rotated 70 deg about gravity and shifted
        val worldYaw = Quat.fromRotVec(Vec3(0.0, Math.toRadians(70.0), 0.0))
        val shift = Vec3(3.0, -0.4, 1.5)
        val qPhoneNew = worldYaw * qPhoneOld
        // gyro-carried estimate has a little pitch noise, which must be ignored
        val qPhoneOldNoisy = Quat.fromRotVec(Vec3(0.01, 0.0, 0.0)) * qPhoneOld
        val pPhoneNew = worldYaw.rotate(pPhoneOld) + shift
        val tr = WorldBridge.between(qPhoneOldNoisy, pPhoneOld, qPhoneNew, pPhoneNew)
        val screenNew = WorldBridge.transfer(screenOld, tr)
        // same physical aim -> same screen point in both worlds
        val (oO, dO) = barrel.ray(qPhoneOld, pPhoneOld)
        val (oN, dN) = barrel.ray(qPhoneNew, pPhoneNew)
        val a = screenOld.intersect(oO, dO)!!
        val b = screenNew.intersect(oN, dN)!!
        assertEquals(a.x, b.x, 0.01)
        assertEquals(a.y, b.y, 0.01)
    }
}
