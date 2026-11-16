package dev.lightgun.net

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Wire format, see shared/protocol.md. */
object Protocol {
    const val AIM_MAGIC = 0x4C47
    const val CTRL_MAGIC = 0x4C43
    const val VERSION = 1
    const val AIM_SIZE = 32
    const val DEFAULT_PORT = 47800

    const val FLAG_ON_SCREEN = 1
    const val FLAG_TRACKING = 2
    const val FLAG_GYRO_ONLY = 4
    const val FLAG_CALIBRATED = 8

    const val BTN_TRIGGER = 1
    const val BTN_RELOAD = 2
    const val BTN_A = 4
    const val BTN_B = 8
    const val BTN_START = 16
    const val BTN_SELECT = 32
    const val BTN_RECENTER = 64
}

/** Not thread-safe: use one per sending thread. */
class PacketCodec(key: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
    private val aimBuf = ByteBuffer.allocate(Protocol.AIM_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun tag(data: ByteArray, len: Int): ByteArray {
        mac.update(data, 0, len)
        return mac.doFinal()
    }

    fun encodeAim(gunId: Int, seq: Int, timeNs: Long, x: Float, y: Float, flags: Int, buttons: Int, presses: Int = 0): ByteArray {
        val b = aimBuf
        b.clear()
        b.putShort(Protocol.AIM_MAGIC.toShort()).put(Protocol.VERSION.toByte()).put(gunId.toByte())
            .putInt(seq).putLong(timeNs).putFloat(x).putFloat(y)
            .put(flags.toByte()).put(presses.toByte()).putShort(buttons.toShort())   // presses: see protocol.md
        val arr = b.array()
        val t = tag(arr, 28)
        System.arraycopy(t, 0, arr, 28, 4)
        return arr.copyOf()
    }

    fun encodeCtrl(gunId: Int, seq: Int, msg: JSONObject): ByteArray {
        val json = msg.toString().toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(8 + json.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(Protocol.CTRL_MAGIC.toShort()).put(Protocol.VERSION.toByte()).put(gunId.toByte())
            .putInt(seq).put(json)
        val arr = b.array()
        val t = tag(arr, 8 + json.size)
        System.arraycopy(t, 0, arr, 8 + json.size, 4)
        return arr
    }

    /** Returns the JSON body of a valid control packet, else null. */
    fun decodeCtrl(data: ByteArray, len: Int): JSONObject? {
        if (len < 12) return null
        val t = tag(data, len - 4)
        for (i in 0 until 4) if (t[i] != data[len - 4 + i]) return null
        val b = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        if ((b.short.toInt() and 0xFFFF) != Protocol.CTRL_MAGIC || b.get().toInt() != Protocol.VERSION) return null
        return try {
            JSONObject(String(data, 8, len - 12, Charsets.UTF_8)).takeIf { it.has("type") }
        } catch (e: Exception) { null }
    }
}

data class Pairing(val host: String, val port: Int, val key: ByteArray, val gunId: Int) {
    fun toUri() = "lightgun://$host:$port?k=${key.joinToString("") { "%02x".format(it) }}&v=1&id=$gunId"

    companion object {
        private val RE = Regex("""^lightgun://([^:/?]+)(?::(\d+))?/?\?(.*)$""")

        fun parse(uri: String): Pairing? = try {
            val m = RE.matchEntire(uri.trim())!!
            val q = m.groupValues[3].split("&").associate {
                it.substringBefore("=") to it.substringAfter("=", "")
            }
            val k = q["k"]!!
            require(k.length == 32)
            Pairing(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: Protocol.DEFAULT_PORT,
                ByteArray(16) { k.substring(2 * it, 2 * it + 2).toInt(16).toByte() },
                q["id"]?.toIntOrNull() ?: 0)
        } catch (e: Exception) { null }
    }
}
