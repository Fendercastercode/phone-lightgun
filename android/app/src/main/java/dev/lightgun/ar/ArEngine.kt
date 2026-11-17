package dev.lightgun.ar

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import dev.lightgun.aim.ScreenRect
import dev.lightgun.aim.WorldBridge
import dev.lightgun.fusion.PoseFuser
import dev.lightgun.fusion.Quat
import dev.lightgun.fusion.Vec3
import java.util.EnumSet
import java.util.UUID
import kotlin.math.abs

/** Immutable per-frame state published by the AR thread. */
data class ArSnapshot(
    val tNs: Long,
    val tracking: Boolean,
    val failure: String?,
    val screen: ScreenRect?,
    val featureCount: Int,
    val fps: Double,
)

/** A grayscale CPU camera frame plus the matching camera model, for flow A. */
class CameraFrame(
    val gray: ByteArray, val width: Int, val height: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val q: Quat, val p: Vec3, val tNs: Long,
)

/**
 * Headless ARCore runner: owns the Session on a dedicated thread with an offscreen EGL
 * context (ARCore needs a camera texture even if nothing is rendered).
 */
class ArEngine(private val context: Context, private val fuser: PoseFuser) {
    @Volatile var snapshot = ArSnapshot(0, false, "not started", null, 0, 0.0)
        private set
    @Volatile var sessionId: String = UUID.randomUUID().toString()
        private set
    @Volatile var cameraFrameListener: ((CameraFrame) -> Unit)? = null
    @Volatile var cameraFrameIntervalMs = 100L
    @Volatile var thumbnailListener: ((Bitmap) -> Unit)? = null
    /** Called (AR thread) when the screen anchor can no longer be trusted; re-anchor needed. */
    @Volatile var screenLostListener: ((String) -> Unit)? = null
    @Volatile var anchorJumps = 0
        private set
    /**
     * Bumped every time ARCore loses tracking and comes back in a different map (new, unrelated
     * coordinate origin) - whether or not a screen is anchored yet. Flow A/Q sweep a camera over
     * several seconds assuming one consistent world; a caller mid-sweep should poll this and
     * restart (with a clear "tracking lost" message) rather than let inconsistent frames reach
     * the solver, where they only surface much later as a confusing geometry-mismatch error.
     */
    @Volatile var worldEpoch = 0
        private set

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var session: Session? = null
    @Volatile private var running = false

    // screen anchor (AR thread only)
    private var anchor: Anchor? = null
    private var screenW = 0.0
    private var screenH = 0.0
    private var offU = 0.0; private var offV = 0.0          // applied recenter offset
    private var offTargetU = 0.0; private var offTargetV = 0.0
    private var offRateU = 0.0; private var offRateV = 0.0  // m per ns
    private var lastScreen: ScreenRect? = null
    private var anchorPausedSince = 0L
    private var wasTracking = false
    private var bridged: ScreenRect? = null
    /** Set when tracking restarts after a pause (phone slept / app left): the gyro was off, so
     *  no bridge is possible. If the old anchor doesn't come back quickly, drop the screen so
     *  the app re-locks instead of trusting a stale position. */
    private var coldResume = false
    private var coldSince = 0L
    private var bridgedSince = 0L
    @Volatile var bridges = 0
        private set
    private var lastAnchorCenter: Vec3? = null
    /**
     * The real screen anchor, kept after a gyro bridge was committed as a new anchor. The bridge
     * is only as good as how still the phone was while the camera was blind (a fast swing away
     * moves it tens of cm), so it is a stand-in: the moment ARCore re-recognises the room and
     * this anchor tracks again, it takes over and the aim snaps back to where it was locked.
     */
    private class Saved(val anchor: Anchor, val w: Double, val h: Double, val offU: Double, val offV: Double)
    private var original: Saved? = null
    @Volatile var relocalizations = 0
        private set
    /** The screen is currently a gyro estimate, not the real anchor (aim may be a little off). */
    @Volatile var aimGuessed = false
        private set

    private var lastCamFrame = 0L
    private var lastThumb = 0L
    private var frameCount = 0
    private var fpsT0 = 0L
    private var fps = 0.0
    private var features = 0
    private var featurePeak = 0
    private var pointCloudWarned = false

    // EGL
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun start() {
        if (thread != null) return
        val t = HandlerThread("arcore", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        handler = Handler(t.looper)
        handler!!.post {
            try {
                setupEgl()
                val s = session ?: createSession().also { session = it }
                coldResume = anchor != null; coldSince = 0L
                bindCameraTexture(s)  // new EGL context each start -> new texture
                s.resume()
                applyDisplayGeometry(s)
                running = true
                handler?.post(loop)
            } catch (e: Exception) {
                Log.e(TAG, "ARCore start failed", e)
                snapshot = snapshot.copy(failure = "ARCore: ${e.message}")
            }
        }
    }

    fun stop() {
        val h = handler ?: return
        running = false  // the loop sees this and stops re-posting before the thread quits
        h.post {
            try { session?.pause() } catch (e: Exception) { Log.w(TAG, "pause", e) }
            teardownEgl()
        }
        thread?.quitSafely()
        thread?.join(1000)
        thread = null
        handler = null
        fuser.reset()
    }

    fun destroy() {
        stop()
        session?.close()
        session = null
    }

    /** Place the screen (world frame of the current session) and anchor it at its centre. */
    fun setScreen(rect: ScreenRect) = handler?.post {
        val s = session ?: return@post
        anchor?.detach(); dropOriginal()
        val c = rect.center
        val q = Quat.fromAxes(rect.u, rect.v, rect.n)
        anchor = s.createAnchor(Pose(c.toFloatArray(), q.toFloatArray()))
        screenW = rect.widthM; screenH = rect.heightM
        offU = 0.0; offV = 0.0; offTargetU = 0.0; offTargetV = 0.0
        lastScreen = rect
        lastAnchorCenter = null; anchorPausedSince = 0L; bridged = null
    }

    fun clearScreen() = handler?.post {
        anchor?.detach(); anchor = null; lastScreen = null; dropOriginal()
    }

    private fun dropOriginal() { original?.anchor?.detach(); original = null }

    /** Recenter: shift the screen in its own plane by (du, dv) metres, blended over [blendMs]. */
    fun nudgeScreen(du: Double, dv: Double, blendMs: Double = 200.0) = handler?.post {
        offTargetU += du; offTargetV += dv
        offRateU = (offTargetU - offU) / (blendMs * 1e6)
        offRateV = (offTargetV - offV) / (blendMs * 1e6)
    }

    // ------------------------------------------------------------------ internals

    private fun createSession(): Session {
        val s = Session(context)
        // Highest frame rate first (smoother aim correction), then the largest CPU image
        // (flow A needs enough pixels to find the ArUco corners precisely).
        val configs = s.getSupportedCameraConfigs(CameraConfigFilter(s)
            .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30, CameraConfig.TargetFps.TARGET_FPS_60))
            .setDepthSensorUsage(EnumSet.of(CameraConfig.DepthSensorUsage.DO_NOT_USE)))
        configs.maxWithOrNull(compareBy<CameraConfig>({ it.fpsRange.upper }, { it.imageSize.width * it.imageSize.height }))
            ?.let { s.cameraConfig = it }
        val cfg = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            depthMode = Config.DepthMode.DISABLED
            updateMode = Config.UpdateMode.BLOCKING
            cloudAnchorMode = Config.CloudAnchorMode.DISABLED
            instantPlacementMode = Config.InstantPlacementMode.DISABLED
        }
        s.configure(cfg)
        sessionId = UUID.randomUUID().toString()
        Log.i(TAG, "camera config fps=${s.cameraConfig.fpsRange} size=${s.cameraConfig.imageSize}")
        return s
    }

    /**
     * ARCore wants the display size/rotation even when nothing is drawn; without it every
     * frame logs "Display geometry has an invalid width: 0".
     */
    private fun applyDisplayGeometry(s: Session) {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        val rot = display.rotation
        // activity is locked to landscape: report landscape dimensions
        val w = maxOf(size.x, size.y); val h = minOf(size.x, size.y)
        s.setDisplayGeometry(rot, w, h)
    }

    private fun bindCameraTexture(s: Session) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        s.setCameraTextureName(tex[0])
    }

    private val loop: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val s = session ?: return
            try {
                val frame = s.update()
                onFrame(frame)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: $e")
                snapshot = snapshot.copy(tracking = false, failure = e.javaClass.simpleName)
                fuser.onArFrame(SystemClock.elapsedRealtimeNanos(), Quat.IDENTITY, Vec3.ZERO, false)
            }
            if (running) handler?.post(this)  // don't re-queue once stop() has begun
        }
    }

    private fun mapTimestamp(ts: Long): Long {
        val elapsed = SystemClock.elapsedRealtimeNanos()
        return if (abs(ts - elapsed) < 1_000_000_000L) ts else ts + (elapsed - System.nanoTime())
    }

    private fun onFrame(frame: Frame) {
        val t = mapTimestamp(frame.timestamp)
        val cam = frame.camera
        val tracking = cam.trackingState == TrackingState.TRACKING
        val failure = if (tracking) null else cam.trackingFailureReason.name
        var resumed: Pair<PoseFuser.Fused, Pair<Quat, Vec3>>? = null
        if (tracking) {
            val sp = frame.androidSensorPose
            val qNew = Quat(sp.qx().toDouble(), sp.qy().toDouble(), sp.qz().toDouble(), sp.qw().toDouble())
            val pNew = Vec3(sp.tx().toDouble(), sp.ty().toDouble(), sp.tz().toDouble())
            if (!wasTracking) {
                // Grab the gyro-carried pose in the OLD world before the fuser snaps to the new one.
                val old = fuser.current(t, predict = false)
                if (old.valid && old.gyroOnly) resumed = old to (qNew to pNew)
            }
            fuser.onArFrame(t, qNew, pNew, true)
        } else {
            fuser.onArFrame(t, Quat.IDENTITY, Vec3.ZERO, false)
        }
        wasTracking = tracking

        // blend recenter offset
        if (offU != offTargetU || offV != offTargetV) {
            val dt = 1e9 / maxOf(fps, 30.0)
            offU = step(offU, offTargetU, offRateU * dt)
            offV = step(offV, offTargetV, offRateV * dt)
        }
        original?.let { o ->
            when (o.anchor.trackingState) {
                TrackingState.TRACKING -> if (tracking) {
                    Log.i(TAG, "original screen anchor re-localized: replacing the committed gyro bridge")
                    anchor?.detach()
                    anchor = o.anchor
                    screenW = o.w; screenH = o.h
                    offU = o.offU; offV = o.offV; offTargetU = o.offU; offTargetV = o.offV
                    original = null; bridged = null; anchorPausedSince = 0L; lastAnchorCenter = null
                    relocalizations++
                }
                TrackingState.STOPPED -> { o.anchor.detach(); original = null }
                else -> Unit
            }
        }
        val a = anchor
        if (resumed != null) worldEpoch++
        // Tracking just came back. If the anchor isn't tracking, ARCore is in a new world frame:
        // carry the screen over with the gyro bridge instead of demanding a re-calibration.
        if (resumed != null && a != null && a.trackingState != TrackingState.TRACKING) {
            val base = lastScreen
            if (base != null) {
                val (old, nw) = resumed
                val tr = WorldBridge.between(old.q, old.p, nw.first, nw.second)
                bridged = WorldBridge.transfer(base, tr)
                bridgedSince = t
                lastScreen = bridged
                bridges++
                Log.i(TAG, "tracking resumed in a new world: screen carried over by gyro bridge")
            }
        }
        if (a != null) when (a.trackingState) {
            TrackingState.TRACKING -> {
                anchorPausedSince = 0L
                if (coldResume) Log.i(TAG, "screen anchor recovered after resume")
                coldResume = false
                if (bridged != null) Log.i(TAG, "original anchor re-localized: dropping gyro bridge")
                bridged = null
                val ap = a.pose  // re-read every frame: loop closure moves anchors
                val c = Vec3(ap.tx().toDouble(), ap.ty().toDouble(), ap.tz().toDouble())
                lastAnchorCenter?.let { if ((c - it).norm() > 0.05) anchorJumps++ } // map correction
                lastAnchorCenter = c
                lastScreen = ScreenRect.fromAnchor(c,
                    Quat(ap.qx().toDouble(), ap.qy().toDouble(), ap.qz().toDouble(), ap.qw().toDouble()),
                    screenW, screenH, offU, offV)
            }
            TrackingState.STOPPED -> if (coldResume && bridged == null) { coldResume = false; loseScreen("phone was paused", keepOld = true) }
                else lastScreen?.let { reanchor(it, "anchor stopped") } ?: loseScreen("anchor stopped")
            TrackingState.PAUSED -> {
                // Camera tracks but our anchor doesn't: we're in a different ARCore map. Keep
                // using the bridged screen; if the old map never comes back, commit the bridge
                // as a fresh anchor so it benefits from the new map's corrections.
                if (coldResume && bridged == null) {
                    if (!tracking) Unit
                    else if (coldSince == 0L) coldSince = t
                    else if (t - coldSince > COLD_GRACE_NS) { coldResume = false; loseScreen("phone was paused", keepOld = true) }
                } else if (!tracking) anchorPausedSince = 0L
                else if (anchorPausedSince == 0L) anchorPausedSince = t
                else if (t - anchorPausedSince > COMMIT_BRIDGE_NS) {
                    val sc = bridged ?: lastScreen
                    if (sc != null) reanchor(sc, "old map not recovered", keepOld = true) else loseScreen("world reset")
                }
            }
        }

        aimGuessed = lastScreen != null && (bridged != null || original != null)
        frameCount++
        if (fpsT0 == 0L) fpsT0 = t
        if (t - fpsT0 > 1_000_000_000L) {
            fps = frameCount * 1e9 / (t - fpsT0)
            frameCount = 0; fpsT0 = t
            features = featurePeak; featurePeak = 0
        }
        // ARCore's point cloud is per-frame and sparse; keep the best count over each second
        if (tracking) try {
            frame.acquirePointCloud().use { pc ->
                val n = pc.points.remaining() / 4
                if (n > featurePeak) featurePeak = n
            }
        } catch (e: Exception) {
            if (!pointCloudWarned) { Log.w(TAG, "point cloud unavailable: $e"); pointCloudWarned = true }
        }
        snapshot = ArSnapshot(t, tracking, failure, lastScreen, features, fps)

        val now = SystemClock.elapsedRealtime()
        val camL = cameraFrameListener
        val thumbL = thumbnailListener
        val wantCam = camL != null && tracking && now - lastCamFrame >= cameraFrameIntervalMs
        val wantThumb = thumbL != null && now - lastThumb >= 500
        if (wantCam || wantThumb) {
            try {
                frame.acquireCameraImage().use { img ->
                    val w = img.width; val h = img.height
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    if (wantCam) {
                        lastCamFrame = now
                        val gray = ByteArray(w * h)
                        for (row in 0 until h) {
                            buf.position(row * rs)
                            buf.get(gray, row * w, w)
                        }
                        val intr = cam.imageIntrinsics
                        val f = intr.focalLength; val pp = intr.principalPoint
                        val cp = cam.pose
                        camL!!(CameraFrame(gray, w, h, f[0], f[1], pp[0], pp[1],
                            Quat(cp.qx().toDouble(), cp.qy().toDouble(), cp.qz().toDouble(), cp.qw().toDouble()),
                            Vec3(cp.tx().toDouble(), cp.ty().toDouble(), cp.tz().toDouble()), t))
                    }
                    if (wantThumb) {
                        lastThumb = now
                        val step = 8
                        val tw = w / step; val th = h / step
                        val px = IntArray(tw * th)
                        for (y in 0 until th) for (x in 0 until tw) {
                            val v = buf.get(y * step * rs + x * step).toInt() and 0xFF
                            px[y * tw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                        }
                        thumbL!!(Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888))
                    }
                }
            } catch (e: NotYetAvailableException) {
                // first frames
            } catch (e: Exception) {
                Log.w(TAG, "camera image: $e")
            }
        }
    }

    /** Replace the anchor with one at [rect] (world frame of the current map). AR thread only. */
    private fun reanchor(rect: ScreenRect, reason: String, keepOld: Boolean = false) {
        val s = session ?: return
        Log.i(TAG, "re-anchoring screen: $reason")
        val old = anchor
        // keep the first real anchor (not a bridge of a bridge) to come back to if the room is recognised again
        if (keepOld && old != null && original == null) original = Saved(old, screenW, screenH, offU, offV)
        else old?.detach()
        anchor = s.createAnchor(Pose(rect.center.toFloatArray(), Quat.fromAxes(rect.u, rect.v, rect.n).toFloatArray()))
        screenW = rect.widthM; screenH = rect.heightM
        offU = 0.0; offV = 0.0; offTargetU = 0.0; offTargetV = 0.0  // rect already includes the offset
        lastScreen = rect; bridged = null; anchorPausedSince = 0L; lastAnchorCenter = null
    }

    private fun loseScreen(reason: String, keepOld: Boolean = false) {
        Log.w(TAG, "screen anchor lost: $reason")
        val old = anchor
        // After the app was paused the anchor is usually just not re-recognised YET: keep it, so
        // the lock comes back by itself once the camera sees the room again (even mid lock-on).
        if (keepOld && old != null && original == null) original = Saved(old, screenW, screenH, offU, offV)
        else { old?.detach(); if (!keepOld) dropOriginal() }
        anchor = null; lastScreen = null
        anchorPausedSince = 0L; lastAnchorCenter = null
        screenLostListener?.invoke(reason)
    }

    private fun step(cur: Double, target: Double, maxStep: Double): Double {
        val d = target - cur
        val s = abs(maxStep).coerceAtLeast(1e-6)
        return if (abs(d) <= s) target else cur + s * Math.signum(d)
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, n, 0)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun teardownEgl() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    companion object {
        private const val TAG = "ArEngine"
        private const val COMMIT_BRIDGE_NS = 8_000_000_000L
        private const val COLD_GRACE_NS = 2_500_000_000L
    }
}
