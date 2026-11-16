package dev.lightgun.aim

import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import kotlin.math.atan2

/**
 * Carries the screen across an ARCore world reset.
 *
 * When tracking drops, the gyro keeps the phone's orientation in the OLD world frame and its
 * position is frozen. When tracking resumes in a NEW frame, the same physical phone pose is
 * known in both frames, so old->new = poseNew * poseOld^-1. Both ARCore worlds are
 * gravity-aligned, so only the yaw part of the rotation is trusted (pitch/roll differences are
 * gyro noise). Translation error = how far the phone moved while blind; a recenter fixes it.
 */
object WorldBridge {
    data class Transform(val yaw: Quat, val t: Vec3) {
        fun apply(p: Vec3) = yaw.rotate(p) + t
        fun applyDir(d: Vec3) = yaw.rotate(d)
    }

    fun between(qOld: Quat, pOld: Vec3, qNew: Quat, pNew: Vec3): Transform {
        val r = qNew * qOld.conj()
        val x = r.rotate(Vec3(1.0, 0.0, 0.0))
        // rotation about +Y by θ maps (1,0,0) to (cosθ, 0, -sinθ)
        val theta = atan2(-x.z, x.x)
        val yaw = Quat.fromRotVec(Vec3(0.0, theta, 0.0))
        return Transform(yaw, pNew - yaw.rotate(pOld))
    }

    fun transfer(s: ScreenRect, tr: Transform) =
        ScreenRect(tr.apply(s.s0), tr.applyDir(s.u).normalized(), tr.applyDir(s.v).normalized(), s.widthM, s.heightM)
}
