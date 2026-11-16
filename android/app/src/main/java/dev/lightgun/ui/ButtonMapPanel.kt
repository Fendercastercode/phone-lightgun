package dev.lightgun.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightgun.GunController
import dev.lightgun.input.ButtonMap
import dev.lightgun.input.GunAction

private fun actionColor(a: GunAction) = when (a) {
    GunAction.TRIGGER -> Neon.Red
    GunAction.RELOAD -> Neon.Cyan
    GunAction.A -> Neon.Lime
    GunAction.B -> Neon.Yellow
    GunAction.START, GunAction.SELECT -> Neon.Magenta
    GunAction.RECENTER -> Neon.Orange
    GunAction.OFF -> Neon.Dim
}

private fun actionLabel(a: GunAction) = if (a == GunAction.TRIGGER) "FIRE" else a.label

/**
 * Options panel. Tap a function (FIRE, RELOAD...), then press the phone or controller button
 * you want for it. A key belongs to one function at a time; a function can have several keys.
 */
@Composable
fun ButtonMapPanel(gun: GunController) {
    val s = gun.settings
    var map by remember { mutableStateOf(s.buttonMap) }
    var listeningFor by remember { mutableStateOf<GunAction?>(null) }
    var justMapped by remember { mutableStateOf<Int?>(null) }

    fun save(m: Map<Int, GunAction>) { map = m; s.buttonMap = m; gun.applySettings() }

    fun listen(a: GunAction) {
        if (listeningFor == a) { listeningFor = null; gun.learnKey = null; return }  // tap again = cancel
        listeningFor = a
        gun.learnKey = { code ->
            listeningFor = null
            justMapped = code
            save(map + (code to a))  // moves the key off whatever it did before
            gun.haptics.reload()
        }
    }

    DisposableEffect(Unit) { onDispose { gun.learnKey = null } }

    RetroPanel("BUTTON MAP", accent = Neon.Orange) {
        Text("TAP A FUNCTION, THEN PRESS THE PHONE BUTTON (E.G. VOLUME UP) YOU WANT FOR IT.",
            style = arcade(10.sp, Neon.Yellow, weight = FontWeight.Bold))
        GunAction.entries.filter { it != GunAction.OFF }.forEach { a ->
            val keys = map.filterValues { it == a }.keys.sortedBy { if (it == 24 || it == 25) 0 else 1 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArcadeButton(actionLabel(a), { listen(a) }, Modifier.width(150.dp), small = true,
                    selected = listeningFor == a, color = actionColor(a))
                Column {
                    if (listeningFor == a) {
                        Blink { al ->
                            Text("PRESS A BUTTON NOW...  (TAP ${actionLabel(a)} AGAIN TO CANCEL)", Modifier.alpha(al),
                                style = arcade(11.sp, Neon.Yellow, glow = Neon.Yellow))
                        }
                    } else if (keys.isEmpty()) {
                        Text("NOT MAPPED", style = arcade(10.sp, Neon.Dim))
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        keys.take(4).forEach { code ->
                            ArcadeButton("${ButtonMap.name(code)} ✖", { save(map - code) }, small = true,
                                color = if (code == justMapped) Neon.Yellow else Neon.White)
                        }
                        if (keys.size > 4) Text("+${keys.size - 4}", style = arcade(10.sp, Neon.Dim))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArcadeButton("RESET TO DEFAULTS", { justMapped = null; listeningFor = null; gun.learnKey = null
                save(ButtonMap.DEFAULT) }, small = true, color = Neon.Dim)
        }
        Text("TAP A BUTTON CHIP (✖) TO UNMAP IT - IT THEN WORKS NORMALLY AGAIN (E.G. VOLUME). " +
            "POWER, BACK, HOME AND SAMSUNG'S SIDE KEY CAN'T BE MAPPED.", style = arcade(9.sp, Neon.Dim))
    }
}
