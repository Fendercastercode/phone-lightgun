package dev.lightgun.net

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** A PC the phone has paired with, plus what we know about its screen/calibration. */
data class SavedHost(
    val id: String,                    // pairing key (hex): identifies the PC even if its IP changes
    val uri: String,
    val name: String = "",
    val displayId: String? = null,
    val displayLabel: String = "",
    val sizeKnown: Boolean = false,
    val calibratedDisplay: String? = null,
    val lastUsed: Long = 0L,
) {
    val pairing get() = Pairing.parse(uri)
    val ip get() = pairing?.host ?: "?"
    /** Calibrated for the screen the PC is driving now (as of the last contact). */
    val calibrated get() = sizeKnown && displayId != null && calibratedDisplay == displayId

    fun toJson() = JSONObject().put("id", id).put("uri", uri).put("name", name).put("displayId", displayId)
        .put("displayLabel", displayLabel).put("sizeKnown", sizeKnown).put("calibratedDisplay", calibratedDisplay)
        .put("lastUsed", lastUsed)

    companion object {
        fun fromJson(o: JSONObject) = SavedHost(o.getString("id"), o.getString("uri"), o.optString("name"),
            o.optString("displayId").ifEmpty { null }, o.optString("displayLabel"), o.optBoolean("sizeKnown"),
            o.optString("calibratedDisplay").ifEmpty { null }, o.optLong("lastUsed"))
    }
}

/** Persistent list of paired PCs, most recently used first. Thread-safe. */
class HostBook(private val sp: SharedPreferences) {
    private val lock = Any()

    fun all(): List<SavedHost> = synchronized(lock) {
        val s = sp.getString("hosts", null) ?: return emptyList()
        try {
            val a = JSONArray(s)
            (0 until a.length()).map { SavedHost.fromJson(a.getJSONObject(it)) }.sortedByDescending { it.lastUsed }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(list: List<SavedHost>) {
        sp.edit().putString("hosts", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    fun update(id: String, f: (SavedHost) -> SavedHost) = synchronized(lock) {
        save(all().map { if (it.id == id) f(it) else it })
    }

    /** Add a newly scanned PC or refresh its address. */
    fun upsert(p: Pairing): SavedHost = synchronized(lock) {
        val id = idOf(p)
        val list = all()
        val old = list.firstOrNull { it.id == id }
        val h = (old ?: SavedHost(id, p.toUri())).copy(uri = p.toUri(), lastUsed = System.currentTimeMillis())
        save(list.filter { it.id != id } + h)
        h
    }

    fun forget(id: String) = synchronized(lock) { save(all().filter { it.id != id }) }

    companion object {
        fun idOf(p: Pairing) = p.key.joinToString("") { "%02x".format(it) }
    }
}

/** Result of asking a PC "are you there?". */
data class ProbeResult(val online: Boolean, val name: String = "", val displayId: String? = null,
                       val displayLabel: String = "", val sizeKnown: Boolean = false)

object HostProbe {
    private val pool = Executors.newCachedThreadPool { Thread(it, "probe").apply { isDaemon = true } }

    /** Probe all [hosts] in parallel; returns within ~[timeoutMs]. Call off the main thread. */
    fun probeAll(hosts: List<SavedHost>, timeoutMs: Int = 700): Map<String, ProbeResult> {
        val futures: Map<String, Future<ProbeResult>> = hosts.associate { h -> h.id to pool.submit<ProbeResult> { probe(h, timeoutMs) } }
        return futures.mapValues { (_, f) ->
            try { f.get(timeoutMs + 300L, TimeUnit.MILLISECONDS) } catch (e: Exception) { ProbeResult(false) }
        }
    }

    private fun probe(h: SavedHost, timeoutMs: Int): ProbeResult {
        val p = h.pairing ?: return ProbeResult(false)
        return try {
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                val codec = PacketCodec(p.key)
                val data = codec.encodeCtrl(p.gunId, 1, JSONObject().put("type", "probe").put("t", System.nanoTime()))
                s.send(DatagramPacket(data, data.size, InetSocketAddress(p.host, p.port)))
                val buf = ByteArray(4096)
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val msg = codec.decodeCtrl(pkt.data, pkt.length) ?: return ProbeResult(false)
                if (msg.optString("type") != "probe_ack") return ProbeResult(false)
                val d = msg.optJSONObject("display")
                ProbeResult(true, msg.optString("host_name"), d?.optString("id"),
                    d?.let { "${it.optInt("w_px")}x${it.optInt("h_px")}" } ?: "", !msg.isNull("screen_size_m"))
            }
        } catch (e: Exception) { ProbeResult(false) }
    }
}
