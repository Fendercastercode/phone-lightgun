package dev.lightgun

import android.content.Context
import dev.lightgun.aim.Barrel
import dev.lightgun.net.Pairing
import org.json.JSONObject

data class GripProfile(val name: String, val barrel: Barrel, val note: String = "")

/** A button's position and size on the Play screen, as fractions (0..1) of the whole screen. */
data class ButtonRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun toJson(): JSONObject = JSONObject().apply { put("x", x); put("y", y); put("w", w); put("h", h) }
    companion object {
        fun fromJson(o: JSONObject) = ButtonRect(o.getDouble("x").toFloat(), o.getDouble("y").toFloat(),
            o.getDouble("w").toFloat(), o.getDouble("h").toFloat())
    }
}

/** SharedPreferences-backed settings and stored calibration. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("lightgun", Context.MODE_PRIVATE)
    private val assets = context.assets

    /** All PCs this phone has paired with. */
    val hosts = dev.lightgun.net.HostBook(sp)

    init {
        // migrate the single saved pairing from older versions into the PC list
        if (sp.getString("hosts", null) == null) sp.getString("pairing", null)?.let { Pairing.parse(it) }?.let {
            val h = hosts.upsert(it)
            sp.getString("last_display", null)?.let { d -> hosts.update(h.id) { x -> x.copy(calibratedDisplay = d) } }
        }
    }

    var pairing: Pairing?
        get() = sp.getString("pairing", null)?.let { Pairing.parse(it) }
        set(v) = sp.edit().putString("pairing", v?.toUri()).apply()

    var predictionMs: Float
        get() = sp.getFloat("prediction_ms", 16f)
        set(v) = sp.edit().putFloat("prediction_ms", v).apply()
    var minCutoff: Float
        get() = sp.getFloat("min_cutoff", 1.0f)
        set(v) = sp.edit().putFloat("min_cutoff", v).apply()
    var beta: Float
        get() = sp.getFloat("beta", 0.007f)
        set(v) = sp.edit().putFloat("beta", v).apply()
    var fusionGain: Float
        get() = sp.getFloat("fusion_gain", 0.1f)
        set(v) = sp.edit().putFloat("fusion_gain", v).apply()
    var sendRateHz: Int
        get() = sp.getInt("send_rate_hz", 200)
        set(v) = sp.edit().putInt("send_rate_hz", v).apply()
    var triggerOnRight: Boolean
        get() = sp.getBoolean("trigger_right", true)
        set(v) = sp.edit().putBoolean("trigger_right", v).apply()
    var autoReanchor: Boolean
        get() = sp.getBoolean("auto_reanchor", true)
        set(v) = sp.edit().putBoolean("auto_reanchor", v).apply()
    var dimDuringPlay: Boolean
        get() = sp.getBoolean("dim", true)
        set(v) = sp.edit().putBoolean("dim", v).apply()

    var flickReload: Boolean
        get() = sp.getBoolean("flick_reload", true)
        set(v) = sp.edit().putBoolean("flick_reload", v).apply()
    /** Angular speed (rad/s) a flick must exceed. Lower = more sensitive. */
    var flickThreshold: Float
        get() = sp.getFloat("flick_threshold", 9f)
        set(v) = sp.edit().putFloat("flick_threshold", v).apply()
    var offscreenReload: Boolean
        get() = sp.getBoolean("offscreen_reload", true)
        set(v) = sp.edit().putBoolean("offscreen_reload", v).apply()
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()
    var clickStrength: Float
        get() = sp.getFloat("hap_click", 255f)
        set(v) = sp.edit().putFloat("hap_click", v).apply()
    var beatMs: Float
        get() = sp.getFloat("hap_beat", 28f)
        set(v) = sp.edit().putFloat("hap_beat", v).apply()
    var recoilStrength: Float
        get() = sp.getFloat("hap_recoil", 255f)
        set(v) = sp.edit().putFloat("hap_recoil", v).apply()
    var fadePercent: Float
        get() = sp.getFloat("hap_fade", 100f)
        set(v) = sp.edit().putFloat("hap_fade", v).apply()
    /** 0 = system click effect. */
    var reloadStrength: Float
        get() = sp.getFloat("hap_reload", 0f)
        set(v) = sp.edit().putFloat("hap_reload", v).apply()
    var gunshotMs: Float
        get() = sp.getFloat("gunshot_ms", 55f)
        set(v) = sp.edit().putFloat("gunshot_ms", v).apply()

    /** Physical key code -> gun action. */
    var buttonMap: Map<Int, dev.lightgun.input.GunAction>
        get() = dev.lightgun.input.ButtonMap.fromJson(sp.getString("button_map", null))
        set(v) = sp.edit().putString("button_map", dev.lightgun.input.ButtonMap.toJson(v)).apply()

    var shotFx: Boolean
        get() = sp.getBoolean("shot_fx", true)
        set(v) = sp.edit().putBoolean("shot_fx", v).apply()

    /** Custom position/size for each Play-screen button, keyed by button id. Missing ids use the default layout. */
    var playLayout: Map<String, ButtonRect>
        get() = sp.getString("play_layout", null)?.let { s ->
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { k -> ButtonRect.fromJson(o.getJSONObject(k)) }
        } ?: emptyMap()
        set(v) = sp.edit().putString("play_layout",
            JSONObject().apply { v.forEach { (k, r) -> put(k, r.toJson()) } }.toString()).apply()

    /** Host display id ("\\.\DISPLAY1:2560x1440") the current calibration belongs to. */
    var lastDisplayId: String?
        get() = sp.getString("last_display", null)
        set(v) = sp.edit().putString("last_display", v).apply()

    /** Tutorial steps the player has completed at least once. */
    var tutorialSteps: Set<String>
        get() = sp.getStringSet("tutorial_steps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("tutorial_steps", HashSet(v)).apply()

    var tutorialDone: Boolean
        get() = sp.getBoolean("tutorial_done", false)
        set(v) = sp.edit().putBoolean("tutorial_done", v).apply()

    var activeProfile: String
        get() = sp.getString("profile", "calibrated") ?: "calibrated"
        set(v) = sp.edit().putString("profile", v).apply()

    /** Barrel found by flow B / D on this phone. Values with an implausible offset (old bug) are dropped. */
    var calibratedBarrel: Barrel?
        get() = sp.getString("barrel_calibrated", null)?.let { Barrel.fromJson(JSONObject(it)) }
            ?.takeIf { it.t.norm() <= 0.2 }
        set(v) = sp.edit().putString("barrel_calibrated", v?.toJson()?.toString()).apply()

    fun profiles(): List<GripProfile> {
        val out = ArrayList<GripProfile>()
        calibratedBarrel?.let { out.add(GripProfile("calibrated", it, "from flow B/D")) }
        out.add(GripProfile("camera-axis", Barrel.CAMERA_AXIS, "hold like a viewfinder"))
        try {
            assets.list("grip_profiles")?.sorted()?.forEach { f ->
                val o = JSONObject(assets.open("grip_profiles/$f").bufferedReader().readText())
                out.add(GripProfile(o.optString("name", f.removeSuffix(".json")),
                    Barrel.fromJson(o.getJSONObject("barrel")), o.optString("description")))
            }
        } catch (e: Exception) { /* no bundled profiles */ }
        return out
    }

    /** Name of the profile actually in use ("calibrated" falls back to the camera axis until B/D runs). */
    fun effectiveProfileName(): String = profiles().firstOrNull { it.name == activeProfile }?.name
        ?: if (calibratedBarrel != null) "calibrated" else "camera-axis (aim with the back camera)"

    fun barrel(): Barrel = profiles().firstOrNull { it.name == activeProfile }?.barrel
        ?: calibratedBarrel ?: Barrel.CAMERA_AXIS
}
