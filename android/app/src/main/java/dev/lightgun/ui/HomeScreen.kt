package dev.lightgun.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.AimState
import dev.lightgun.GunController
import dev.lightgun.net.HostProbe
import dev.lightgun.net.Pairing
import dev.lightgun.net.ProbeResult
import dev.lightgun.net.SavedHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}

/** Which popup is open on the PC screen. */
private sealed interface Popup {
    data class Host(val id: String) : Popup
    data object Add : Popup
    data object Invite : Popup
}

/**
 * Start screen: small tiles for the PCs this phone knows (name + connection light) and an
 * "add PC" tile. Tapping a tile opens a popup with the options and explanations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(gun: GunController, aim: AimState, onScanQr: () -> Unit, onUse: (SavedHost) -> Unit,
               onRecalibrate: (SavedHost) -> Unit) {
    var hosts by remember { mutableStateOf(gun.settings.hosts.all()) }
    var probes by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    var popup by remember { mutableStateOf<Popup?>(null) }

    // refresh online status every few seconds (the connected PC is known from the live link)
    LaunchedEffect(Unit) {
        while (true) {
            hosts = gun.settings.hosts.all()
            val toProbe = hosts.filter { it.id != gun.hostId || gun.link?.connected != true }
            val r = withContext(Dispatchers.IO) { HostProbe.probeAll(toProbe) }
            probes = r
            r.forEach { (id, p) ->
                if (p.online) gun.settings.hosts.update(id) {
                    it.copy(name = p.name.ifEmpty { it.name }, displayId = p.displayId ?: it.displayId,
                        displayLabel = p.displayLabel.ifEmpty { it.displayLabel }, sizeKnown = p.sizeKnown)
                }
            }
            hosts = gun.settings.hosts.all()
            delay(3000)
        }
    }

    fun status(h: SavedHost): PcStatus {
        val connected = h.id == gun.hostId && gun.link?.connected == true
        return when {
            connected -> PcStatus.CONNECTED
            probes[h.id]?.online == true -> PcStatus.ONLINE
            probes.containsKey(h.id) -> PcStatus.OFFLINE
            else -> PcStatus.CHECKING
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            ChromeTitle("SELECT YOUR PC", 22.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                hosts.forEach { h ->
                    val s = status(h)
                    Tile(h.name.ifEmpty { "PC ${h.ip}" }, s.color, s.label, if (s == PcStatus.CONNECTED) Neon.Lime else Neon.Cyan) {
                        popup = Popup.Host(h.id)
                    }
                }
                Tile("+ ADD PC", Neon.Lime, null, Neon.Lime) { popup = Popup.Add }
            }
        }

        when (val p = popup) {
            is Popup.Host -> hosts.firstOrNull { it.id == p.id }?.let { h ->
                HostPopup(h, status(h), gun.newScreen && h.id == gun.hostId,
                    onClose = { popup = null },
                    onUse = { popup = null; onUse(h) },
                    onRecalibrate = { popup = null; onRecalibrate(h) },
                    onForget = { gun.settings.hosts.forget(h.id); hosts = gun.settings.hosts.all(); popup = null },
                    onInvite = { popup = Popup.Invite })
            } ?: run { popup = null }
            Popup.Add -> AddPopup(onClose = { popup = null },
                onScanQr = { popup = null; onScanQr() },
                onCode = { pairing -> popup = null; onUse(gun.settings.hosts.upsert(pairing)) })
            Popup.Invite -> InvitePopup(gun) { popup = null }
            null -> {}
        }
    }
}

private enum class PcStatus(val label: String, val color: Color) {
    CONNECTED("CONNECTED", Neon.Lime), ONLINE("READY", Neon.Cyan),
    OFFLINE("OFFLINE", Neon.Red), CHECKING("CHECKING...", Neon.Dim)
}

/** Small square-ish tile: name + one status light. */
@Composable
private fun Tile(name: String, ledColor: Color, label: String?, border: Color, onClick: () -> Unit) {
    Column(Modifier.width(170.dp).heightIn(min = 64.dp).background(Neon.Panel).bevel(border, 3.dp)
        .clickable(onClick = onClick).padding(10.dp), verticalArrangement = Arrangement.Center) {
        Text(name.uppercase(), style = arcade(14.sp, Neon.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Led(true, ledColor, label)
        }
    }
}

/** Dimmed full-screen layer with a centred retro panel. Tapping outside closes it. */
@Composable
internal fun PopupFrame(title: String, accent: Color, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))
        .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f)
            .clickable(remember { MutableInteractionSource() }, null) { /* swallow taps */ }) {
            RetroPanel(title, accent = accent) {
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

@Composable
private fun Line(text: String, color: Color = Neon.White, size: Int = 12) {
    Text(text, style = arcade(size.sp, color, weight = FontWeight.Bold).copy(lineHeight = (size + 6).sp))
}

@Composable
private fun HostPopup(h: SavedHost, s: PcStatus, newScreen: Boolean, onClose: () -> Unit, onUse: () -> Unit,
                      onRecalibrate: () -> Unit, onForget: () -> Unit, onInvite: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val ready = h.calibrated && !newScreen
    PopupFrame(h.name.ifEmpty { "PC" }.uppercase(), if (ready) Neon.Lime else Neon.Orange, onClose) {
        Row {
            Led(s == PcStatus.CONNECTED || s == PcStatus.ONLINE, s.color, s.label)
            Led(ready, Neon.Magenta, if (ready) "CALIBRATED" else "NEEDS CALIBRATION")
        }
        Text("${h.ip} · screen ${h.displayLabel.ifEmpty { "?" }} · last used ${ago(h.lastUsed)}",
            style = arcade(10.sp, Neon.Dim, weight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        when {
            s == PcStatus.OFFLINE -> Line("Can't reach this PC. On the PC, double-click start-host.bat and make sure " +
                    "the phone is on the same Wi-Fi.", Neon.Orange)
            ready -> Line("All set for this screen. PLAY connects, then you point the phone's BACK CAMERA at the TV for a second to lock on (phone sideways, its screen facing you) - then it's straight into the game.")
            else -> Line("This PC's screen hasn't been set up yet (or it's a different screen than last time). " +
                    "SET UP takes you through measuring the TV - about a minute, once per screen.", Neon.Yellow)
        }
        Spacer(Modifier.height(8.dp))
        // only a PC that is connected or answering can be played on / set up
        val reachable = s == PcStatus.CONNECTED || s == PcStatus.ONLINE
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeButton(if (ready) "▶ PLAY" else "▶ SET UP", if (ready) onUse else onRecalibrate,
                color = if (!reachable) Neon.Dim else if (ready) Neon.Lime else Neon.Orange, enabled = reachable)
            if (ready) ArcadeButton("RECALIBRATE", onRecalibrate, small = true, color = Neon.Cyan, enabled = reachable)
        }
        if (!reachable) Line(if (s == PcStatus.CHECKING) "Looking for this PC..." else
            "PLAY unlocks when this PC shows READY or CONNECTED.", Neon.Orange, 10)
        if (ready) Line("Recalibrate if you moved the TV or the aim feels off.", Neon.Dim, 10)
        if (s == PcStatus.CONNECTED) ArcadeButton("+ ADD PLAYER 2", onInvite, small = true, color = Neon.Cyan)
        Spacer(Modifier.height(6.dp))
        Row {
            ArcadeButton(if (confirm) "TAP AGAIN TO FORGET" else "FORGET THIS PC",
                { if (confirm) onForget() else confirm = true }, small = true, color = if (confirm) Neon.Red else Neon.Dim)
            ArcadeButton("CLOSE", onClose, small = true, color = Neon.Dim)
        }
    }
}

@Composable
private fun AddPopup(onClose: () -> Unit, onScanQr: () -> Unit, onCode: (Pairing) -> Unit) {
    var showCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    PopupFrame("ADD A PC", Neon.Lime, onClose) {
        Line("1. On the PC, double-click start-host.bat")
        Line("2. In its window, click SHOW PAIRING QR")
        Line("3. Tap SCAN QR CODE and point the phone at the code")
        Spacer(Modifier.height(4.dp))
        Line("Then the app walks you through measuring that PC's screen (once).", Neon.Dim, 10)
        Spacer(Modifier.height(8.dp))
        Row {
            ArcadeButton("+ SCAN QR CODE", onScanQr, color = Neon.Lime)
            ArcadeButton(if (showCode) "HIDE" else "TYPE CODE", { showCode = !showCode }, small = true, color = Neon.Dim)
        }
        if (showCode) {
            Line("Paste the lightgun://... text shown under the QR code.", Neon.Dim, 10)
            BasicTextField(code, { code = it }, singleLine = true, textStyle = arcade(11.sp, Neon.Yellow),
                cursorBrush = SolidColor(Neon.Yellow),
                modifier = Modifier.fillMaxWidth().background(Color.Black).padding(8.dp))
            ArcadeButton("CONNECT", { Pairing.parse(code)?.let(onCode) }, small = true, color = Neon.Cyan,
                enabled = Pairing.parse(code) != null)
        }
        ArcadeButton("CLOSE", onClose, small = true, color = Neon.Dim)
    }
}

/** QR code bitmap for [text] (pairing codes are short, 512 px is plenty). */
fun qrBitmap(text: String, size: Int = 512): android.graphics.Bitmap =
    com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)

/** "Player 2: scan this" popup shown on player 1's phone. */
@Composable
fun InvitePopup(gun: GunController, onClose: () -> Unit) {
    val player = if (gun.player == 1) 2 else 1
    val code = gun.invite(player)
    PopupFrame("ADD PLAYER $player", Neon.Cyan, onClose) {
        if (code == null) {
            Text("Connect to a PC first.", style = arcade(12.sp, Neon.White, weight = FontWeight.Bold))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val bmp = androidx.compose.runtime.remember(code) { qrBitmap(code) }
                androidx.compose.foundation.Image(bmp.asImageBitmap(), "pairing code",
                    Modifier.size(200.dp).background(Color.White).padding(8.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ON THE OTHER PHONE:", style = arcade(12.sp, Neon.Yellow))
                    Text("1. Install and open RetroSight\n2. Tap JOIN A FRIEND'S GAME\n   (or PCs > + ADD PC > SCAN QR)\n" +
                            "3. Scan this code\n4. Point at the TV, press ST to join in",
                        style = arcade(11.sp, Neon.White, weight = FontWeight.Bold).copy(lineHeight = 17.sp))
                    Spacer(Modifier.height(4.dp))
                    Text("No setup needed - this PC already knows the TV.", style = arcade(10.sp, Neon.Lime, weight = FontWeight.Bold))
                }
            }
        }
        ArcadeButton("CLOSE", onClose, small = true, color = Neon.Dim)
    }
}
