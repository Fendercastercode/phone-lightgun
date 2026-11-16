package dev.lightgun.fusion

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm() = sqrt(this dot this)
    fun normalized(): Vec3 { val n = norm(); return if (n < 1e-15) this else this * (1.0 / n) }
    fun toList() = listOf(x, y, z)
    fun toFloatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        fun of(a: FloatArray) = Vec3(a[0].toDouble(), a[1].toDouble(), a[2].toDouble())
        fun of(a: List<Double>) = Vec3(a[0], a[1], a[2])
    }
}

/** Hamilton quaternion, ARCore component order [x, y, z, w]. */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(q: Quat) = Quat(
        w * q.x + x * q.w + y * q.z - z * q.y,
        w * q.y - x * q.z + y * q.w + z * q.x,
        w * q.z + x * q.y - y * q.x + z * q.w,
        w * q.w - x * q.x - y * q.y - z * q.z,
    )

    fun conj() = Quat(-x, -y, -z, w)
    fun dot(q: Quat) = x * q.x + y * q.y + z * q.z + w * q.w
    fun neg() = Quat(-x, -y, -z, -w)

    fun normalized(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        return Quat(x / n, y / n, z / n, w / n)
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2.0
        return v + t * w + (q cross t)
    }

    fun toFloatArray() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    fun toList() = listOf(x, y, z, w)

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)
        fun of(a: FloatArray) = Quat(a[0].toDouble(), a[1].toDouble(), a[2].toDouble(), a[3].toDouble())

        fun fromRotVec(r: Vec3): Quat {
            val th = r.norm()
            if (th < 1e-12) return Quat(r.x / 2, r.y / 2, r.z / 2, 1.0).normalized()
            val s = sin(th / 2) / th
            return Quat(r.x * s, r.y * s, r.z * s, cos(th / 2))
        }

        /** Rotation whose columns are the given orthonormal axes (u, v, n). */
        fun fromAxes(u: Vec3, v: Vec3, n: Vec3): Quat {
            val m00 = u.x; val m10 = u.y; val m20 = u.z
            val m01 = v.x; val m11 = v.y; val m21 = v.z
            val m02 = n.x; val m12 = n.y; val m22 = n.z
            val tr = m00 + m11 + m22
            val q = if (tr > 0) {
                val s = sqrt(tr + 1.0) * 2
                Quat((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25 * s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2
                Quat(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            } else if (m11 > m22) {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2
                Quat((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
            } else {
                val s = sqrt(1.0 + m22 - m00 - m11) * 2
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
            }
            return q.normalized()
        }

        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var d = a.dot(b)
            if (d < 0) { b = b.neg(); d = -d }
            if (d > 0.9995) {
                return Quat(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y),
                    a.z + t * (b.z - a.z), a.w + t * (b.w - a.w)).normalized()
            }
            val th = acos(d.coerceIn(-1.0, 1.0))
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quat(wa * a.x + wb * b.x, wa * a.y + wb * b.y, wa * a.z + wb * b.z, wa * a.w + wb * b.w)
        }

        /** Angle between two orientations in radians. */
        fun angle(a: Quat, b: Quat): Double = 2 * acos(abs(a.dot(b)).coerceAtMost(1.0))
    }
}
