package dev.lightgun.aim

import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Barrel ray in the phone (Android sensor) frame. See shared/math.md. */
data class Barrel(val b: Vec3, val t: Vec3) {
    fun ray(q: Quat, p: Vec3): Pair<Vec3, Vec3> = Pair(p + q.rotate(t), q.rotate(b))

    fun toJson() = JSONObject().put("b", JSONArray(b.toList())).put("t", JSONArray(t.toList()))

    companion object {
        /** Default: back-camera axis. */
        val CAMERA_AXIS = Barrel(Vec3(0.0, 0.0, -1.0), Vec3.ZERO)

        fun fromJson(o: JSONObject) = Barrel(vec(o.getJSONArray("b")).normalized(),
            o.optJSONArray("t")?.let { vec(it) } ?: Vec3.ZERO)
    }
}

data class Hit(val x: Double, val y: Double, val onScreen: Boolean, val distance: Double)

/** World-space screen rectangle: top-left corner, unit axes (right, down), physical size. */
data class ScreenRect(val s0: Vec3, val u: Vec3, val v: Vec3, val widthM: Double, val heightM: Double) {
    val n: Vec3 get() = u cross v
    val center: Vec3 get() = point(0.5, 0.5)

    fun point(x: Double, y: Double) = s0 + u * (x * widthM) + v * (y * heightM)

    fun intersect(o: Vec3, d: Vec3): Hit? {
        val nn = n
        val denom = nn dot d
        if (abs(denom) < 1e-9) return null
        val s = (nn dot (s0 - o)) / denom
        if (s <= 0) return null
        val rel = o + d * s - s0
        val x = (u dot rel) / widthM
        val y = (v dot rel) / heightM
        return Hit(x, y, x in 0.0..1.0 && y in 0.0..1.0, s)
    }

    fun toJson() = JSONObject().put("s0", JSONArray(s0.toList())).put("u", JSONArray(u.toList()))
        .put("v", JSONArray(v.toList())).put("w_m", widthM).put("h_m", heightM)

    companion object {
        fun fromJson(o: JSONObject) = ScreenRect(vec(o.getJSONArray("s0")), vec(o.getJSONArray("u")),
            vec(o.getJSONArray("v")), o.getDouble("w_m"), o.getDouble("h_m"))

        /** Rebuild from an anchor pose (centre + rotation with columns u, v, n) and an in-plane offset. */
        fun fromAnchor(center: Vec3, rot: Quat, w: Double, h: Double, du: Double, dv: Double): ScreenRect {
            val u = rot.rotate(Vec3(1.0, 0.0, 0.0))
            val v = rot.rotate(Vec3(0.0, 1.0, 0.0))
            val c = center + u * du + v * dv
            return ScreenRect(c - u * (w / 2) - v * (h / 2), u, v, w, h)
        }
    }
}

internal fun vec(a: JSONArray) = Vec3(a.getDouble(0), a.getDouble(1), a.getDouble(2))
