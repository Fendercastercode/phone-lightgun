package dev.lightgun.input

import android.view.KeyEvent
import dev.lightgun.net.Protocol
import org.json.JSONObject

/** What a physical button does. RECENTER is handled on the phone; OFF leaves the key to Android. */
enum class GunAction(val label: String, val bit: Int) {
    TRIGGER("TRIGGER", Protocol.BTN_TRIGGER),
    RELOAD("RELOAD", Protocol.BTN_RELOAD),
    A("A", Protocol.BTN_A),
    B("B", Protocol.BTN_B),
    START("START", Protocol.BTN_START),
    SELECT("SELECT", Protocol.BTN_SELECT),
    RECENTER("RECENTER", 0),
    OFF("OFF", 0);

    fun next() = entries[(ordinal + 1) % entries.size]
}

object ButtonMap {
    val DEFAULT: Map<Int, GunAction> = linkedMapOf(
        KeyEvent.KEYCODE_VOLUME_DOWN to GunAction.TRIGGER,
        KeyEvent.KEYCODE_VOLUME_UP to GunAction.RELOAD,
        // BLE camera-shutter remotes usually send ENTER or volume-up
        KeyEvent.KEYCODE_ENTER to GunAction.TRIGGER,
        KeyEvent.KEYCODE_CAMERA to GunAction.TRIGGER,
        // game controllers
        KeyEvent.KEYCODE_BUTTON_R2 to GunAction.TRIGGER,
        KeyEvent.KEYCODE_BUTTON_R1 to GunAction.TRIGGER,
        KeyEvent.KEYCODE_BUTTON_A to GunAction.TRIGGER,
        KeyEvent.KEYCODE_BUTTON_B to GunAction.RELOAD,
        KeyEvent.KEYCODE_BUTTON_L1 to GunAction.RELOAD,
        KeyEvent.KEYCODE_BUTTON_L2 to GunAction.RELOAD,
        KeyEvent.KEYCODE_BUTTON_X to GunAction.A,
        KeyEvent.KEYCODE_BUTTON_Y to GunAction.B,
        KeyEvent.KEYCODE_BUTTON_START to GunAction.START,
        KeyEvent.KEYCODE_BUTTON_SELECT to GunAction.SELECT,
    )

    /** Keys that must never be captured (navigation would break). */
    val RESERVED = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_POWER)

    fun name(code: Int): String = when (code) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME DOWN"
        KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME UP"
        KeyEvent.KEYCODE_ENTER -> "ENTER / SHUTTER"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace("BUTTON_", "PAD ").replace('_', ' ')
    }

    fun toJson(m: Map<Int, GunAction>) = JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v.name) } }.toString()

    fun fromJson(s: String?): Map<Int, GunAction> {
        if (s.isNullOrEmpty()) return DEFAULT
        return try {
            val o = JSONObject(s)
            val out = LinkedHashMap<Int, GunAction>()
            o.keys().forEach { k -> runCatching { out[k.toInt()] = GunAction.valueOf(o.getString(k)) } }
            out
        } catch (e: Exception) { DEFAULT }
    }
}
