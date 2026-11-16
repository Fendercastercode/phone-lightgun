package dev.lightgun.calib

import android.util.Log
import dev.lightgun.ar.CameraFrame
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Flow A: detects the host's ArUco 4x4_50 markers and builds `aruco_obs` messages. */
/** [onDetections] gets the marker ids seen, mean marker side as a fraction of image width, and whether the view was sent. */
class ArucoScanner(private val send: (JSONObject) -> Unit,
                   private val onDetections: (ids: Set<Int>, sideFrac: Float, sent: Boolean) -> Unit) {
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "aruco") }
    private val busy = AtomicBoolean(false)
    private val detector: ArucoDetector? by lazy {
        if (!OpenCVLoader.initLocal()) { Log.e(TAG, "OpenCV init failed"); null } else {
            val params = DetectorParameters().apply {
                set_cornerRefinementMethod(Objdetect.CORNER_REFINE_SUBPIX)
            }
            ArucoDetector(Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50), params)
        }
    }

    /** Called on the AR thread; drops the frame if the previous one is still being processed. */
    fun submit(f: CameraFrame) {
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try { process(f) } catch (e: Exception) { Log.w(TAG, "detect: $e") } finally { busy.set(false) }
        }
    }

    private fun process(f: CameraFrame) {
        val det = detector ?: return
        val img = Mat(f.height, f.width, CvType.CV_8UC1)
        img.put(0, 0, f.gray)
        val corners = ArrayList<Mat>()
        val ids = Mat()
        det.detectMarkers(img, corners, ids)
        val markers = JSONObject()
        val seen = HashSet<Int>()
        var side = 0.0
        for (i in 0 until ids.rows()) {
            val id = ids.get(i, 0)[0].toInt()
            if (id > 4) continue
            val c = FloatArray(8)
            corners[i].get(0, 0, c)
            markers.put(id.toString(), JSONArray(c.map { it.toDouble() }))
            seen += id
            side += Math.hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble())
        }
        img.release(); ids.release(); corners.forEach { it.release() }
        val sideFrac = if (seen.isEmpty()) 0f else (side / seen.size / f.width).toFloat()
        val shouldSend = markers.length() >= 2
        onDetections(seen, sideFrac, shouldSend)
        if (!shouldSend) return
        send(JSONObject().put("type", "aruco_obs")
            .put("K", JSONArray(listOf(f.fx, f.fy, f.cx, f.cy).map { it.toDouble() }))
            .put("size", JSONArray(listOf(f.width, f.height)))
            .put("q", JSONArray(f.q.toList())).put("p", JSONArray(f.p.toList()))
            .put("markers", markers))
    }

    fun close() = exec.shutdown()

    companion object { private const val TAG = "Aruco" }
}
