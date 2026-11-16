package dev.lightgun.net

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP link to the PC host. Aim packets are sent synchronously from the caller's thread
 * (the sensor thread); control messages go through a single-thread executor.
 *
 * Robustness:
 *  - [connected] goes false again when the PC stops answering (pongs come every second), so the
 *    UI shows the truth and the hello is re-sent.
 *  - If the PC's IP changed (DHCP), [helloBroadcast] finds it again: any reply that passes the
 *    pairing key's HMAC is the right PC, so the link follows it and [onHostMoved] saves the new IP.
 */
class HostLink(private val pairing: Pairing, private val onCtrl: (JSONObject) -> Unit) {
    private val socket = DatagramSocket().apply {
        sendBufferSize = 1 shl 16
        soTimeout = 500
        broadcast = true
    }
    @Volatile private var addr = InetSocketAddress(pairing.host, pairing.port)
    private val aimCodec = PacketCodec(pairing.key)
    private val ctrlCodec = PacketCodec(pairing.key)
    private val rxCodec = PacketCodec(pairing.key)
    private val ctrlExec = Executors.newSingleThreadExecutor { Thread(it, "ctrl-tx") }
    private val aimSeq = AtomicInteger(0)
    private val ctrlSeq = AtomicInteger(0)
    @Volatile private var running = true
    @Volatile private var acked = false
    @Volatile private var lastRxNs = 0L
    /** True while the PC has answered recently (it pongs every second). */
    val connected get() = acked && SystemClock.elapsedRealtimeNanos() - lastRxNs < STALE_NS
    @Volatile var rttMs = -1.0
        private set
    @Volatile var lastHelloAck: JSONObject? = null
        private set
    @Volatile var aimSent = 0L
        private set
    /** Network quality as the PC sees our aim stream: % packets lost, extra delay (p95, ms). */
    @Volatile var netLoss = 0.0
        private set
    @Volatile var netLateMs = 0.0
        private set
    /** Called (rx thread) with the PC's new IP when it answered from a different address. */
    @Volatile var onHostMoved: ((String) -> Unit)? = null

    private val rxThread = Thread({ rxLoop() }, "udp-rx").apply { isDaemon = true; start() }

    fun sendAim(timeNs: Long, x: Float, y: Float, flags: Int, buttons: Int, presses: Int = 0) {
        val data = aimCodec.encodeAim(pairing.gunId, aimSeq.incrementAndGet(), timeNs, x, y, flags, buttons, presses)
        try {
            socket.send(DatagramPacket(data, data.size, addr))
            aimSent++
        } catch (e: Exception) {
            // Wi-Fi hiccup; next packet will try again
        }
    }

    fun sendCtrl(msg: JSONObject) = sendCtrlTo(msg, null)

    private fun sendCtrlTo(msg: JSONObject, to: InetSocketAddress?) {
        if (!running) return
        ctrlExec.execute {
            try {
                val data = ctrlCodec.encodeCtrl(pairing.gunId, ctrlSeq.incrementAndGet(), msg)
                socket.send(DatagramPacket(data, data.size, to ?: addr))
            } catch (e: Exception) {
                Log.w(TAG, "ctrl send failed: $e")
            }
        }
    }

    fun ping(stats: JSONObject) = sendCtrl(JSONObject().put("type", "ping")
        .put("t", SystemClock.elapsedRealtimeNanos()).put("stats", stats))

    private fun helloMsg(device: String) = JSONObject().put("type", "hello").put("device", device)
        .put("app_version", "0.1.0")

    fun hello(device: String) = sendCtrl(helloMsg(device))

    /** Ask the whole local network: finds the PC again if its IP changed. */
    fun helloBroadcast(device: String) =
        sendCtrlTo(helloMsg(device), InetSocketAddress(InetAddress.getByName("255.255.255.255"), pairing.port))

    private fun rxLoop() {
        val buf = ByteArray(8192)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                socket.receive(pkt)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (!running) break else continue
            }
            val msg = rxCodec.decodeCtrl(pkt.data, pkt.length) ?: continue   // wrong key: not our PC
            lastRxNs = SystemClock.elapsedRealtimeNanos()
            if (pkt.address != addr.address) {
                // our PC (it knows the key) answered from a new address: follow it
                Log.i(TAG, "PC moved: ${addr.address?.hostAddress} -> ${pkt.address.hostAddress}")
                addr = InetSocketAddress(pkt.address, pairing.port)
                try { onHostMoved?.invoke(pkt.address.hostAddress ?: "") } catch (e: Exception) { Log.e(TAG, "onHostMoved", e) }
            }
            when (msg.optString("type")) {
                "pong" -> {
                    rttMs = (SystemClock.elapsedRealtimeNanos() - msg.optLong("t")) / 1e6
                    msg.optJSONObject("net")?.let { n -> netLoss = n.optDouble("loss", 0.0); netLateMs = n.optDouble("late_ms", 0.0) }
                }
                "hello_ack" -> { acked = true; lastHelloAck = msg }
            }
            try { onCtrl(msg) } catch (e: Exception) { Log.e(TAG, "onCtrl", e) }
        }
    }

    fun close() {
        running = false
        ctrlExec.shutdown()
        socket.close()
    }

    companion object {
        private const val TAG = "HostLink"
        private const val STALE_NS = 3_500_000_000L
    }
}
