package dev.lightgun

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.ar.core.ArCoreApk
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.lightgun.input.ButtonMap
import dev.lightgun.input.GunAction
import dev.lightgun.net.HostBook
import dev.lightgun.net.Pairing
import dev.lightgun.net.Protocol
import dev.lightgun.ui.LightGunApp
import dev.lightgun.ui.Route

class MainActivity : ComponentActivity() {
    private lateinit var gun: GunController
    private var route by mutableStateOf(Route.HOME)
    private var installRequested = false

    private val scan = registerForActivityResult(ScanContract()) { r ->
        val p = r.contents?.let { Pairing.parse(it) }
        if (p != null) {
            gun.connect(p)
            Toast.makeText(this, "Paired with ${p.host}:${p.port}", Toast.LENGTH_SHORT).show()
            if (p.gunId > 0) {
                // joining a friend's game: the PC already knows the TV, so skip setup and go play
                // (PLAY locks on by itself when the gun is pointed at the TV)
                gun.settings.tutorialDone = true
                route = Route.PLAY; onRouteChanged(route)
                Toast.makeText(this, "You're player ${p.gunId + 1} - point at the TV, then press ST", Toast.LENGTH_LONG).show()
            } else if (route == Route.HOME) {
                // A freshly added pc needs the full walkthrough, not just the bare 3-step
                // Calibrate screen - that one has no camera-check/anchor step at all, so a new
                // pairing landing there skipped straight from "pair" to "measure the TV" with no
                // chance to see or save what the camera locked onto. Per-pc tutorial progress
                // (GunController.hostTutorialSteps) means this is a real fresh run for this pc
                // even if other pcs already finished it.
                route = Route.TUTORIAL; onRouteChanged(route)
            }
        } else if (r.contents != null) {
            Toast.makeText(this, "Not a light-gun pairing code", Toast.LENGTH_LONG).show()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, "Camera permission is required for tracking", Toast.LENGTH_LONG).show()
        else onRouteChanged(route)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gun = GunController.get(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        goImmersive()
        // start on the PC list (pick a calibrated PC or add a new one); first run: guided setup
        route = if (!gun.settings.tutorialDone) Route.TUTORIAL else Route.HOME
        // Only auto-reconnect to a pc that's still actually in the saved list - otherwise a
        // forgotten pc (which clears hostId but can't un-set this legacy single-pairing field
        // retroactively) would just come right back on the next cold launch.
        if (!gun.settings.tutorialDone) gun.settings.pairing?.let { p ->
            if (gun.settings.hosts.get(HostBook.idOf(p)) != null) gun.connect(p)
        }
        setContent {
            LightGunApp(gun, route, onNavigate = { route = it; onRouteChanged(it) }, onScanQr = ::scanQr,
                onUseHost = { h ->
                    if (gun.hostId != h.id || gun.link?.connected != true) h.pairing?.let { gun.connect(it) }
                    // calibrated PC + screen: straight to PLAY (quick lock-on runs automatically).
                    // Not calibrated yet (never finished setup, or typed in its pairing code
                    // instead of scanning it): the full walkthrough, same reasoning as a fresh
                    // QR pairing above - not the bare Calibrate screen, which has no camera-check.
                    if (h.calibrated) gun.lockOnPlay = true   // always re-lock on the TV, then play
                    route = if (h.calibrated) Route.PLAY else Route.TUTORIAL
                    onRouteChanged(route)
                })
        }
    }

    private fun scanQr() {
        gun.stop() // the scanner needs the camera
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR code shown on the PC").setBeepEnabled(false).setOrientationLocked(false))
    }

    private fun arReady(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return false
        }
        return try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; false }
                else -> true
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ARCore unavailable: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun onRouteChanged(r: Route) {
        val needsAr = r == Route.CALIBRATE || r == Route.PLAY || r == Route.SETTINGS || r == Route.TUTORIAL
        if (needsAr && arReady()) gun.start()
        val lp = window.attributes
        lp.screenBrightness = if (r == Route.PLAY && gun.settings.dimDuringPlay) 0.05f
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        try { window.setSustainedPerformanceMode(r == Route.PLAY) } catch (e: Exception) { }
    }

    /** Full-screen game mode: no status/nav bar stealing taps; swipe from an edge shows them briefly. */
    private fun goImmersive() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()  // dialogs/QR scanner/notification shade can bring the bars back
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        onRouteChanged(route)
    }

    override fun onPause() {
        super.onPause()
        gun.stop()
    }

    // ---------------------------------------------------------------- physical buttons
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in ButtonMap.RESERVED) return super.dispatchKeyEvent(event)
        // Options > Button map > LEARN: report the next key instead of acting on it
        gun.learnKey?.let { cb ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) { gun.learnKey = null; cb(code) }
            return true
        }
        if (!gun.running) return super.dispatchKeyEvent(event)
        val action = gun.buttonMap[code]
        if (action == null || action == GunAction.OFF) return super.dispatchKeyEvent(event)  // Android handles it
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) gun.doAction(action, true)
            KeyEvent.ACTION_UP -> gun.doAction(action, false)
        }
        return true
    }
}
