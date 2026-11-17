package dev.lightgun.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Attract-mode background that cycles through three eras of arcade light-gun look (all original art):
 *  - 1987 NEON: synthwave sun + scrolling grid, voxel bad guys
 *  - 1994 DIGITIZED: grainy dusk city street, "photographed actor" style thugs, bullet holes on the glass
 *  - 1999 POLYGON: flat-shaded low-poly warehouse the camera dollies through, low-poly soldiers
 * Each switch is a burst of TV static. [update] runs once per frame, [drawBackdrop]/[draw] paint any size.
 */
class AttractMode(private val horizon: Float = 0.55f) {

    enum class Era(val label: String) { NEON("1987 · NEON"), DIGITIZED("1994 · DIGITIZED"), POLYGON("1999 · POLYGON"), HD("2006 · HD") }

    // ------------------------------------------------------------------ characters (original designs)
    private class Sprite(val rows: List<String>, val palette: Map<Char, Color>) {
        val w = rows.maxOf { it.length }
        val h = rows.size
        val cells = buildList {
            rows.forEachIndexed { y, r -> r.forEachIndexed { x, c -> palette[c]?.let { add(Triple(x, y, it)) } } }
        }
    }

    /** One character type: drawn as voxels/pixels from [sprite], or as polygons ([poly] id). */
    private class Kind(val w: Int, val h: Int, val height: Float, val flying: Boolean = false,
                       val sprite: Sprite? = null, val poly: Int = -1, val colors: List<Color> = emptyList(),
                       val actor: Int = -1)

    private fun spriteKind(s: Sprite, height: Float, flying: Boolean = false) =
        Kind(s.w, s.h, height, flying, s, colors = s.cells.map { it.third }.distinct())

    private val neonKinds = listOf(
        // boxy patrol bot with antennae and a glowing visor
        spriteKind(Sprite(listOf(
            "..R....R..", "..R....R..", ".MMMMMMMM.", ".MYYMMYYM.", ".MMMMMMMM.",
            ".MKKKKKKM.", "MMMMMMMMMM", "M.MMMMMM.M", "M.MM..MM.M", "..MM..MM..",
        ), mapOf('R' to Neon.Red, 'M' to Color(0xFF8E9AAF), 'Y' to Neon.Yellow, 'K' to Color(0xFF2B2D42))), 0.5f),
        // one-eyed tentacle blob
        spriteKind(Sprite(listOf(
            "..PPPPPP..", ".PPPPPPPP.", "PPWWWWWWPP", "PPWWKKWWPP", "PPWWWWWWPP", ".PPPPPPPP.", ".P.PPPP.P.", "P..P..P..P",
        ), mapOf('P' to Neon.Lime, 'W' to Color.White, 'K' to Color(0xFF1B1B3A))), 0.4f),
        // mohawk street punk in shades
        spriteKind(Sprite(listOf(
            "....OO....", "...OOOO...", "...SSSS...", "..KKSSKK..", "...SSSS...", "...SRRS...",
            "..JJJJJJ..", ".JJJJJJJJ.", ".J.JJJJ.J.", "...B..B...", "...B..B...",
        ), mapOf('O' to Neon.Orange, 'S' to Color(0xFFE0A47A), 'K' to Color(0xFF111111), 'R' to Neon.Red,
            'J' to Neon.Magenta, 'B' to Color(0xFF3A3AFF))), 0.55f),
        // hover drone
        spriteKind(Sprite(listOf(
            "...CCCC...", "..CWWWWC..", "MMMMMMMMMM", "MYMMYMMYMM", ".MMMMMMMM.", "...R..R...",
        ), mapOf('C' to Neon.Cyan, 'W' to Color(0xFFBFF8FF), 'M' to Color(0xFF6A4C93), 'Y' to Neon.Yellow,
            'R' to Neon.Red)), 0.3f, flying = true),
    )

    // 1994: digitized-actor style people (see DigitizedActors)
    private val actorFrames = DigitizedActors.build()
    private val digitizedKinds = List(DigitizedActors.count) { Kind(10, 20, 0.78f, actor = it) }

    private val polyKinds = listOf(
        Kind(10, 16, 0.7f, poly = 0, colors = listOf(Color(0xFF6F86A3), Color(0xFF4A5A6E), Color(0xFF2E3A4A), Color(0xFFFF3355))),
        Kind(10, 16, 0.75f, poly = 1, colors = listOf(Color(0xFF8C8C5A), Color(0xFF5A5A3A), Color(0xFF3A3A26), Color(0xFFFFB000))),
        Kind(10, 6, 0.3f, flying = true, poly = 2, colors = listOf(Color(0xFFB8C4D0), Color(0xFF7A8794), Color(0xFF4A5560), Color(0xFFFF2244))),
    )

    private val hdKinds = listOf(
        // armoured operative: dark plates, cyan rim light, glowing visor
        Kind(10, 16, 0.72f, poly = 10, colors = listOf(Color(0xFF2B3440), Color(0xFF12171E), Color(0xFF66E0FF), Color(0xFF00D0FF))),
        // cyborg: gunmetal with orange optics
        Kind(10, 16, 0.75f, poly = 11, colors = listOf(Color(0xFF4A4F57), Color(0xFF1C1F24), Color(0xFFFFB070), Color(0xFFFF6A00))),
        // sleek drone
        Kind(10, 5, 0.3f, flying = true, poly = 12, colors = listOf(Color(0xFF3A4048), Color(0xFF15181C), Color(0xFFBFE8FF), Color(0xFFFF2040))),
    )

    private fun kinds(era: Int = this.era) = when (era) { 0 -> neonKinds; 1 -> digitizedKinds; 2 -> polyKinds; else -> hdKinds }

    // ------------------------------------------------------------------ actors
    /** [sx] is the screen x (0..1 of width); menus keep their text on the left, so bad guys roam the right. */
    private class Enemy(var kind: Int, var sx: Float, var z: Float, var vx: Float, var phase: Float) {
        var alive = true
        var respawnIn = 0f
        var hp = 3
        var cover = -1        // index into covers() when hiding behind something
        var duck = 0f         // 0 = standing up, 1 = fully hidden
    }

    private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val size: Float,
                           val color: Color, var life: Float, val spin: Float)

    private class Popup(val x: Float, val y: Float, var life: Float, val text: String)
    /** A shot 1994 actor falling backwards. */
    private class Faller(val actor: Int, val x: Float, val ground: Float, val height: Float, var life: Float)
    private class Hole(val x: Float, val y: Float, var life: Float, val rot: Float)

    private class Crosshair(val color: Color, var x: Float, var y: Float) {
        var target = -1
        var dwell = 0f
        var cool = 0f
        var flash = 0f
        var spin = 0f
    }

    // ------------------------------------------------------------------ cover objects
    /** Something to hide behind. [w]/[h] are world sizes (screen heights at distance 1); [type] picks the look. */
    private class Cover(val sx: Float, val z: Float, val w: Float, val h: Float, val type: Int)

    private val eraCovers = listOf(
        // 1987: glowing grid crates and a pillar
        listOf(Cover(0.66f, 2.0f, 0.55f, 0.42f, 0), Cover(0.86f, 2.9f, 0.6f, 0.45f, 0), Cover(0.76f, 3.8f, 0.3f, 0.6f, 1)),
        // 1994: parked sedan, dumpster, oil drums
        listOf(Cover(0.72f, 2.1f, 1.15f, 0.42f, 10), Cover(0.9f, 3.2f, 0.7f, 0.45f, 11), Cover(0.63f, 3.6f, 0.45f, 0.42f, 12)),
        // 1999: wooden crates, steel drum stack
        listOf(Cover(0.68f, 2.0f, 0.5f, 0.45f, 20), Cover(0.88f, 2.8f, 0.55f, 0.48f, 20), Cover(0.77f, 3.7f, 0.42f, 0.44f, 21)),
        // 2006: concrete barrier, rooftop AC unit, sandbags
        listOf(Cover(0.7f, 2.0f, 0.85f, 0.4f, 30), Cover(0.9f, 2.9f, 0.55f, 0.5f, 31), Cover(0.63f, 3.6f, 0.6f, 0.38f, 32)),
    )

    private fun covers() = eraCovers[era.coerceIn(0, eraCovers.size - 1)]


    private val rnd = Random(7)
    private val fx = Random(11)          // per-frame noise (grain, static) - kept apart from the simulation
    var era = 0
        private set
    private var eraT = 0f
    private val enemies = ArrayList<Enemy>().also { list -> repeat(7) { list += spawn(Enemy(-1, 0f, 0f, 0f, 0f), list) } }
    private val particles = ArrayList<Particle>()
    private val popups = ArrayList<Popup>()
    private val holes = ArrayList<Hole>()
    private val flashes = ArrayList<Popup>()
    private val fallers = ArrayList<Faller>()
    private val crosshairs = listOf(Crosshair(Neon.Magenta, 0.3f, 0.5f), Crosshair(Neon.Cyan, 0.7f, 0.5f))
    private var t = 0f

    private fun spawn(e: Enemy, all: List<Enemy> = enemies): Enemy {
        // least-represented character type, so the cast stays varied
        val ks = kinds()
        val counts = IntArray(ks.size)
        all.forEach { if (it !== e && it.alive && it.kind in ks.indices) counts[it.kind]++ }
        val least = counts.minOrNull() ?: 0
        e.kind = counts.indices.filter { counts[it] == least }.random(rnd)
        e.z = 1.4f + rnd.nextFloat() * 2.8f
        e.sx = MIN_X + rnd.nextFloat() * (MAX_X - MIN_X)
        e.vx = (0.02f + rnd.nextFloat() * 0.05f) * if (rnd.nextBoolean()) 1 else -1
        e.phase = rnd.nextFloat() * 6.28f
        e.alive = true
        e.hp = 2 + rnd.nextInt(3)          // takes a few hits
        e.cover = -1; e.duck = 0f
        // about half of them grab a free piece of cover and fight from behind it
        if (!ks[e.kind].flying && rnd.nextFloat() < 0.6f) {
            val free = covers().indices.filter { i -> all.none { it !== e && it.alive && it.cover == i } }
            if (free.isNotEmpty()) {
                val c = covers()[free.random(rnd)]
                e.cover = covers().indexOf(c); e.sx = c.sx + (rnd.nextFloat() - 0.5f) * 0.02f; e.z = c.z + 0.25f
                e.vx = 0f; e.duck = 1f
            }
        }
        return e
    }

    // ------------------------------------------------------------------ projection (normalized 0..1 screen)
    private fun kindOf(e: Enemy) = kinds().let { it[e.kind.mod(it.size)] }
    private fun cellSize(e: Enemy) = kindOf(e).let { it.height / (e.z * it.h) }   // in screen heights
    private fun groundY(z: Float) = horizon + (1f - horizon) * (1.2f / z).coerceAtMost(1f)

    /** Centre of an enemy in normalized coords (x in widths, y in heights). */
    private fun center(e: Enemy): Offset {
        val k = kindOf(e)
        val c = cellSize(e)
        val bob = sin(t * 3f + e.phase) * c * (if (era == 1) 0.25f else 0.6f)
        val lift = if (k.flying) c * k.h * 1.6f else 0f
        val sink = e.duck * c * k.h * 0.8f
        return Offset(e.sx, groundY(e.z) - c * k.h / 2f - lift + bob + sink)
    }

    private fun exposed(e: Enemy) = e.cover < 0 || e.duck < 0.4f

    /** 1994 actors stop now and then to point the gun at the player. */
    private fun aiming(e: Enemy) = if (e.cover >= 0) e.duck < 0.3f else era == 1 && ((t + e.phase * 3f) % 6f) > 4.6f
    private fun muzzleFlash(e: Enemy) = aiming(e) &&
        (if (e.cover >= 0) ((t * 7f + e.phase * 5f) % 1f) < 0.18f
         else ((t + e.phase * 3f) % 6f).let { it in 5.2f..5.3f || it in 5.6f..5.68f })

    // ------------------------------------------------------------------ simulation
    fun update(dt: Float, aspect: Float) {
        t += dt
        eraT += dt
        if (eraT > ERA_SECONDS) {
            eraT = 0f
            era = (era + 1) % Era.entries.size
            particles.clear(); popups.clear(); holes.clear(); flashes.clear(); fallers.clear()
            enemies.forEach { spawn(it) }
            crosshairs.forEach { it.target = -1; it.dwell = 0f }
        }
        for (e in enemies) {
            if (!e.alive) {
                e.respawnIn -= dt
                if (e.respawnIn <= 0f) spawn(e)
                continue
            }
            if (e.cover >= 0) {
                // hide, pop up, shoot for a moment, duck again
                val cyc = (t * 0.45f + e.phase) % 1f
                val target = if (cyc in 0.45f..0.85f) 0f else 1f
                e.duck += (target - e.duck) * (dt * 9f).coerceAtMost(1f)
            } else if (!aiming(e)) e.sx += e.vx * dt
            if (e.sx < MIN_X && e.vx < 0 || e.sx > MAX_X && e.vx > 0) e.vx = -e.vx
        }
        for (c in crosshairs) {
            c.spin += dt * 1.5f
            c.flash = (c.flash - dt * 3f).coerceAtLeast(0f)
            val tgt = enemies.getOrNull(c.target)
            if (tgt == null || !tgt.alive || crosshairs.any { it !== c && it.target == c.target }) {
                // pick the nearest live enemy nobody else is chasing
                c.target = enemies.indices.filter { i -> enemies[i].alive && crosshairs.none { it !== c && it.target == i } }
                    .minByOrNull { i -> val p = center(enemies[i]); hypot((p.x - c.x) * aspect, p.y - c.y) + if (exposed(enemies[i])) 0f else 0.6f } ?: -1
                c.dwell = 0f
                continue
            }
            if (!exposed(tgt) && rnd.nextFloat() < dt * 1.2f && enemies.any { it.alive && exposed(it) }) { c.target = -1; continue }
            val p = center(tgt)
            // eased chase with a little hand wobble
            val k = (dt * 7f).coerceAtMost(1f)
            c.x += (p.x - c.x) * k + sin(t * 7f + c.color.hashCode()) * 0.0008f
            c.y += (p.y - c.y) * k + cos(t * 6f) * 0.0008f
            val d = hypot((p.x - c.x) * aspect, p.y - c.y)
            if (d < 0.05f) c.dwell += dt else c.dwell = 0f
            // rapid fire once roughly on target
            c.cool -= dt
            if (d < 0.05f && c.cool <= 0f) { c.cool = 0.1f + rnd.nextFloat() * 0.07f; shoot(c, tgt, aspect) }
        }
        val g = 1.6f
        particles.removeAll { it.life <= 0f }
        for (q in particles) { q.vy += g * dt; q.x += q.vx * dt; q.y += q.vy * dt; q.life -= dt }
        popups.removeAll { it.life <= 0f }
        popups.forEach { it.life -= dt }
        holes.removeAll { it.life <= 0f }
        holes.forEach { it.life -= dt }
        flashes.removeAll { it.life <= 0f }
        flashes.forEach { it.life -= dt }
        fallers.removeAll { it.life <= 0f }
        fallers.forEach { it.life -= dt }
    }

    /** One shot: impact sparks where it lands; the enemy drops when out of hits. */
    private fun shoot(c: Crosshair, e: Enemy, aspect: Float) {
        c.flash = 0.6f
        val k = kindOf(e)
        val hx = c.x + (rnd.nextFloat() - 0.5f) * 0.03f; val hy = c.y + (rnd.nextFloat() - 0.5f) * 0.04f
        val col = when (era) { 0 -> Neon.Yellow; 1 -> Color(0xFFFFE0A0); 2 -> Color(0xFFFF8040); else -> Color(0xFFFFD27A) }
        val cell = cellSize(e)
        repeat(4) {
            val ang = rnd.nextFloat() * 6.28f
            val sp = 0.1f + rnd.nextFloat() * 0.25f
            particles += Particle(hx, hy, cos(ang) * sp / aspect, sin(ang) * sp - 0.15f, cell * 0.6f,
                if (k.colors.isNotEmpty() && rnd.nextBoolean()) k.colors.random(rnd) else col, 0.25f + rnd.nextFloat() * 0.2f, rnd.nextFloat() * 6f)
        }
        if (era == 1 && rnd.nextFloat() < 0.35f) {
            holes += Hole(hx, hy, 2.5f, rnd.nextFloat() * 6.28f)
            if (holes.size > 14) holes.removeAt(0)
        }
        if (!exposed(e)) return   // ducked: the shot just chips the cover
        if (--e.hp <= 0) fire(c, e, aspect)
    }

    private fun fire(c: Crosshair, e: Enemy, aspect: Float) {
        c.flash = 1f
        c.dwell = 0f
        e.alive = false
        e.respawnIn = 0.3f + rnd.nextFloat() * 0.5f
        val k = kindOf(e)
        val cell = cellSize(e)
        val ctr = center(e)
        if (k.actor >= 0) {
            fallers += Faller(k.actor, ctr.x, groundY(e.z), cell * k.h, 1.4f)
            repeat(6) {   // a little dust where they drop
                val ang = 3.4f + rnd.nextFloat() * 2.6f
                particles += Particle(ctr.x, groundY(e.z) - cell, cos(ang) * 0.08f / aspect, sin(ang) * 0.12f, cell * 0.8f,
                    Color(0xFF8A8070), 0.5f + rnd.nextFloat() * 0.3f, 0f)
            }
        } else if (k.sprite != null) {
            // burst the voxels / pixels outward
            for ((cx, cy, col) in k.sprite.cells) {
                if (era == 1 && rnd.nextFloat() < 0.6f) continue  // digitized: a puff, not a voxel storm
                val px = ctr.x + (cx - k.w / 2f) * cell / aspect
                val py = ctr.y + (cy - k.h / 2f) * cell
                val ang = rnd.nextFloat() * 6.28f
                val sp = 0.15f + rnd.nextFloat() * 0.45f
                particles += Particle(px, py, cos(ang) * sp / aspect, sin(ang) * sp - 0.35f, cell, col,
                    0.6f + rnd.nextFloat() * 0.5f, 0f)
            }
        } else if (era == 3) {
            // glowing sparks + a bloom flash
            repeat(24) {
                val ang = rnd.nextFloat() * 6.28f
                val sp = 0.25f + rnd.nextFloat() * 0.6f
                particles += Particle(ctr.x, ctr.y, cos(ang) * sp / aspect, sin(ang) * sp - 0.3f, cell * 0.9f,
                    if (rnd.nextFloat() < 0.6f) Color(0xFFFFD27A) else k.colors[3], 0.4f + rnd.nextFloat() * 0.5f, 0f)
            }
            flashes += Popup(ctr.x, ctr.y, 0.35f, "")
        } else {
            // polygon shards
            repeat(18) {
                val ang = rnd.nextFloat() * 6.28f
                val sp = 0.2f + rnd.nextFloat() * 0.5f
                particles += Particle(ctr.x, ctr.y, cos(ang) * sp / aspect, sin(ang) * sp - 0.4f,
                    cell * (1.2f + rnd.nextFloat() * 1.5f), k.colors.random(rnd), 0.7f + rnd.nextFloat() * 0.5f,
                    rnd.nextFloat() * 6.28f)
            }
        }
        if (era == 1) {
            holes += Hole(c.x, c.y, 4f, rnd.nextFloat() * 6.28f)
            if (holes.size > 10) holes.removeAt(0)
        }
        popups += Popup(ctr.x, ctr.y, 0.9f, when (era) { 0 -> "+100"; 1 -> "+500"; 2 -> "HIT!"; else -> "+1000" })
    }

    // ------------------------------------------------------------------ backdrops
    /** The scene behind the characters. [phase] 0..1 scrolls; [animate] false shows the static 1987 look. */
    fun drawBackdrop(ds: DrawScope, phase: Float, animate: Boolean) {
        when (if (animate) era else 0) {
            0 -> neonBackdrop(ds, phase)
            1 -> streetBackdrop(ds, phase)
            2 -> warehouseBackdrop(ds)
            else -> rooftopBackdrop(ds)
        }
    }

    private fun neonBackdrop(ds: DrawScope, phase: Float) = with(ds) {
        val w = size.width; val h = size.height
        val hz = h * horizon
        drawRect(Brush.verticalGradient(listOf(Neon.Night, Neon.Deep, Color(0xFF4A0E5C)), 0f, hz), size = Size(w, hz))
        // a few twinkling stars (no sun)
        for (i in 0 until 40) {
            val sx = ((i * 0.6180339f) % 1f) * w; val sy = ((i * 0.3819f + 0.13f) % 1f) * hz * 0.9f
            val tw = 0.35f + 0.65f * (sin(t * 2f + i * 1.7f) * 0.5f + 0.5f)
            drawRect(Color.White.copy(alpha = 0.5f * tw), Offset(sx, sy), Size(3f, 3f))
        }
        // floor grid
        drawRect(Color(0xFF12002E), Offset(0f, hz), Size(w, h - hz))
        val gridColor = Neon.Magenta.copy(alpha = 0.45f)
        for (i in 0..12) {
            val p = ((i + phase) / 12f)
            val y = hz + (h - hz) * p * p
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 2f)
        }
        for (i in -10..10) {
            drawLine(gridColor, Offset(w / 2 + i * w / 60f, hz), Offset(w / 2 + i * w / 8f, h), strokeWidth = 2f)
        }
        drawLine(Neon.Cyan.copy(alpha = 0.7f), Offset(0f, hz), Offset(w, hz), strokeWidth = 3f)
    }

    private class Building(val x: Float, val width: Float, val height: Float, val seed: Int)

    private val farCity = Random(3).let { r -> List(16) { Building(it / 16f * 1.5f, 0.06f + r.nextFloat() * 0.05f, 0.12f + r.nextFloat() * 0.2f, r.nextInt()) } }
    private val nearCity = Random(5).let { r -> List(8) { Building(it / 8f * 1.5f, 0.12f + r.nextFloat() * 0.07f, 0.25f + r.nextFloat() * 0.25f, r.nextInt()) } }

    private fun streetBackdrop(ds: DrawScope, phase: Float) = with(ds) {
        val w = size.width; val h = size.height
        val hz = h * horizon
        // smoggy dusk sky
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0F1430), Color(0xFF4A2A40), Color(0xFFB85A30)), 0f, hz), size = Size(w, hz))
        // two layers of buildings sliding by (on-rails ride)
        fun skyline(list: List<Building>, speed: Float, body: Color, lit: Color, litChance: Float) {
            for (b in list) {
                val x = (((b.x - t * speed) % 1.5f) + 1.5f) % 1.5f - 0.2f
                val left = x * w; val bw = b.width * w; val top = hz - b.height * h
                drawRect(body, Offset(left, top), Size(bw, hz - top))
                // windows: a fixed pattern per building, a few flicker
                val r = Random(b.seed)
                val cols = (bw / (h * 0.03f)).toInt().coerceAtLeast(2)
                val rows = ((hz - top) / (h * 0.04f)).toInt()
                val cw = bw / cols
                for (cy in 0 until rows) for (cx in 0 until cols) {
                    val on = r.nextFloat() < litChance
                    val flick = r.nextFloat() < 0.04f && sin(t * 9f + cx * 3 + cy) > 0.6f
                    if (on && !flick) drawRect(lit, Offset(left + cx * cw + cw * 0.25f, top + cy * h * 0.04f + h * 0.012f),
                        Size(cw * 0.5f, h * 0.018f))
                }
                // the odd neon sign
                if (b.seed % 5 == 0) {
                    val on = sin(t * 4f + b.seed) > -0.3f
                    drawRect(if (on) Color(0xFFFF4FA0) else Color(0xFF5A2040), Offset(left + bw * 0.2f, top + h * 0.03f),
                        Size(bw * 0.6f, h * 0.035f))
                }
            }
        }
        skyline(farCity, 0.008f, Color(0xFF241C28), Color(0xFF6A5A40), 0.18f)
        skyline(nearCity, 0.02f, Color(0xFF3A2C2A), Color(0xFFE8C060), 0.3f)
        // street with sidewalks running to the vanishing point
        val vp = Offset(w * 0.5f, hz)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF45454A)), hz, h), Offset(0f, hz), Size(w, h - hz))
        fun quad(c: Color, a: Offset, b: Offset, cc: Offset, d: Offset? = null) = fillPoly(c, a, b, cc, d)
        quad(Color(0xFF55524E), Offset(0f, h), Offset(w * 0.12f, h), vp, Offset(0f, hz + (h - hz) * 0.15f))
        quad(Color(0xFF55524E), Offset(w, h), Offset(w * 0.88f, h), vp, Offset(w, hz + (h - hz) * 0.15f))
        // centre line dashes coming toward us
        for (i in 0 until 8) {
            val p0 = ((i + phase) / 8f); val p1 = ((i + phase + 0.45f) / 8f)
            val y0 = hz + (h - hz) * p0 * p0; val y1 = hz + (h - hz) * p1 * p1
            val hw0 = w * 0.006f * (0.2f + p0 * 3f); val hw1 = w * 0.006f * (0.2f + p1 * 3f)
            quad(Color(0xFFD8B030), Offset(vp.x - hw0, y0), Offset(vp.x + hw0, y0), Offset(vp.x + hw1, y1), Offset(vp.x - hw1, y1))
        }
    }

    private fun warehouseBackdrop(ds: DrawScope) = with(ds) {
        val w = size.width; val h = size.height
        val hz = h * horizon
        // one-point room: back wall + floor/ceiling/walls as flat-shaded polygons
        val x0 = w * 0.34f; val x1 = w * 0.66f; val y0 = h * 0.2f; val y1 = hz
        fun poly(c: Color, a: Offset, b: Offset, cc: Offset, d: Offset? = null) = fillPoly(c, a, b, cc, d)
        poly(Color(0xFF1E2229), Offset(0f, 0f), Offset(w, 0f), Offset(x1, y0), Offset(x0, y0))              // ceiling
        poly(Color(0xFF3A3F47), Offset(0f, h), Offset(w, h), Offset(x1, y1), Offset(x0, y1))                // floor
        poly(Color(0xFF4C5563), Offset(0f, 0f), Offset(x0, y0), Offset(x0, y1), Offset(0f, h))              // left wall (lit)
        poly(Color(0xFF353C47), Offset(w, 0f), Offset(x1, y0), Offset(x1, y1), Offset(w, h))                // right wall
        poly(Color(0xFF283039), Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1))             // back wall
        // big door on the back wall with a pulsing warning light
        poly(Color(0xFF1A1F26), Offset(w * 0.44f, h * 0.3f), Offset(w * 0.56f, h * 0.3f), Offset(w * 0.56f, y1), Offset(w * 0.44f, y1))
        val pulse = (sin(t * 5f) * 0.5f + 0.5f)
        drawCircle(Color(0xFFFF8A00).copy(alpha = 0.4f + 0.6f * pulse), h * 0.012f, Offset(w * 0.5f, h * 0.26f))
        drawCircle(Color(0xFFFF8A00).copy(alpha = 0.15f * pulse), h * 0.05f, Offset(w * 0.5f, h * 0.26f))
        // depth slices: the camera dollies forward, so pillars/lights/floor seams slide toward us
        val zFar = 7f
        fun k(z: Float) = ((1f / z - 1f / zFar) / (1f - 1f / zFar)).coerceIn(0f, 1f)   // 0 = back wall, 1 = screen edge
        fun lerp(a: Offset, b: Offset, f: Float) = Offset(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
        val spacing = 1.6f
        val scroll = (t * 0.9f) % spacing
        var z = zFar - scroll
        val slices = ArrayList<Float>()
        while (z > 1.05f) { slices += z; z -= spacing }
        for (zs in slices) {           // far -> near
            val ka = k(zs); val kb = k(zs - 0.35f)
            val fog = 1f - ka
            fun fogged(c: Color) = Color(c.red + (0.16f - c.red) * fog * 0.6f, c.green + (0.19f - c.green) * fog * 0.6f,
                c.blue + (0.23f - c.blue) * fog * 0.6f)
            // floor seam
            drawLine(Color(0xFF2A2E35), lerp(Offset(x0, y1), Offset(0f, h), ka), lerp(Offset(x1, y1), Offset(w, h), ka), 2f)
            // pillars on both walls (front face + side face)
            val lt = lerp(Offset(x0, y0), Offset(0f, 0f), ka); val lb = lerp(Offset(x0, y1), Offset(0f, h), ka)
            val lt2 = lerp(Offset(x0, y0), Offset(0f, 0f), kb); val lb2 = lerp(Offset(x0, y1), Offset(0f, h), kb)
            poly(fogged(Color(0xFF6A7686)), lt, lt2, lb2, lb)
            val pw = (lb2.x - lb.x) * 0.5f
            poly(fogged(Color(0xFF7D8A9C)), lt2, Offset(lt2.x + pw, lt2.y + pw * 0.2f), Offset(lb2.x + pw, lb2.y - pw * 0.2f), lb2)
            val rt = lerp(Offset(x1, y0), Offset(w, 0f), ka); val rb = lerp(Offset(x1, y1), Offset(w, h), ka)
            val rt2 = lerp(Offset(x1, y0), Offset(w, 0f), kb); val rb2 = lerp(Offset(x1, y1), Offset(w, h), kb)
            poly(fogged(Color(0xFF4E5866)), rt, rt2, rb2, rb)
            poly(fogged(Color(0xFF5C6878)), rt2, Offset(rt2.x - pw, rt2.y + pw * 0.2f), Offset(rb2.x - pw, rb2.y - pw * 0.2f), rb2)
            // ceiling light strip
            val ca = lerp(Offset(w * 0.47f, y0), Offset(w * 0.4f, 0f), ka); val cb = lerp(Offset(w * 0.53f, y0), Offset(w * 0.6f, 0f), ka)
            val cc = lerp(Offset(w * 0.53f, y0), Offset(w * 0.6f, 0f), kb); val cd = lerp(Offset(w * 0.47f, y0), Offset(w * 0.4f, 0f), kb)
            poly(Color(0xFFE8F0FF).copy(alpha = 0.35f + 0.5f * ka), ca, cb, cc, cd)
        }
        // hazard stripe where the walls meet the floor
        drawLine(Color(0xFFD8A020), Offset(0f, h), Offset(x0, y1), 3f)
        drawLine(Color(0xFFD8A020), Offset(w, h), Offset(x1, y1), 3f)
    }

    // ------------------------------------------------------------------ characters + effects
    fun draw(ds: DrawScope, text: TextMeasurer) = with(ds) {
        val w = size.width; val h = size.height
        val aspect = w / h
        val items: List<Pair<Float, Any>> = (enemies.filter { it.alive }.map { it.z to (it as Any) } +
            covers().map { it.z to (it as Any) }).sortedByDescending { it.first }
        for ((_, item) in items) {
            if (item is Cover) { drawCover(item, w, h); continue }
            val e = item as Enemy
            clipRect(0f, 0f, w, if (e.cover >= 0) groundY(e.z) * h else h) { drawEnemy(e, w, h, aspect) }
        }
        for (f in fallers) {
            val img = actorFrames[f.actor][0]
            val age = 1.4f - f.life
            val ang = -80f * (age / 0.45f).coerceAtMost(1f)            // topple backwards (screen: to the left)
            val a = (f.life / 0.5f).coerceIn(0f, 1f)
            val dh = f.height * h; val dw = dh * DigitizedActors.W / DigitizedActors.H
            val foot = Offset(f.x * w, f.ground * h)
            rotate(ang, foot) {
                drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset((foot.x - dw / 2).toInt(), (foot.y - dh).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                    alpha = a, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
            }
        }
        for (q in particles) {
            val a = q.life.coerceIn(0f, 1f)
            if (era == 2) {
                val c = Offset(q.x * w, q.y * h); val r = q.size * h * 0.6f; val s = q.spin + t * 6f
                drawPath(Path().apply {
                    moveTo(c.x + cos(s) * r, c.y + sin(s) * r)
                    lineTo(c.x + cos(s + 2.3f) * r, c.y + sin(s + 2.3f) * r)
                    lineTo(c.x + cos(s + 4.1f) * r * 0.6f, c.y + sin(s + 4.1f) * r * 0.6f); close()
                }, q.color.copy(alpha = a))
            } else if (era == 3) {
                val c = Offset(q.x * w, q.y * h); val r = q.size * h
                drawCircle(Brush.radialGradient(listOf(q.color.copy(alpha = a * 0.6f), Color.Transparent), c, r * 2.5f), r * 2.5f, c)
                drawCircle(Color.White.copy(alpha = a), r * 0.4f, c)
            } else drawRect(q.color.copy(alpha = a), Offset(q.x * w, q.y * h), Size(q.size * h, q.size * h))
        }
        for (f in flashes) {   // bloom on hits (2006)
            val a = (f.life / 0.35f).coerceIn(0f, 1f)
            val c = Offset(f.x * w, f.y * h); val r = h * (0.25f - 0.15f * a)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f * a), Color(0xFFFFC060).copy(alpha = 0.3f * a),
                Color.Transparent), c, r), r, c)
        }
        // bullet holes in the "glass" (1994)
        for (b in holes) {
            val a = b.life.coerceIn(0f, 1f)
            val c = Offset(b.x * w, b.y * h); val r = h * 0.012f
            for (i in 0 until 5) {
                val ang = b.rot + i * 1.26f
                drawLine(Color.White.copy(alpha = 0.45f * a), c, Offset(c.x + cos(ang) * r * 3f, c.y + sin(ang) * r * 3f), 1.5f)
            }
            drawCircle(Color(0xFF0A0A0A).copy(alpha = 0.9f * a), r, c)
            drawCircle(Color.White.copy(alpha = 0.6f * a), r, c, style = Stroke(1.5f))
        }
        for (p in popups) {
            val a = p.life.coerceIn(0f, 1f)
            val col = when (era) { 0 -> Neon.Yellow; 1 -> Color.White; 2 -> Color(0xFFFF5040); else -> Color(0xFFBFF4FF) }
            drawText(text, p.text, Offset(p.x * w - 30f, (p.y - (0.9f - p.life) * 0.12f) * h),
                TextStyle(color = col.copy(alpha = a), fontSize = 20.sp, fontWeight = FontWeight.Black, fontFamily = Arcade))
        }
        for (c in crosshairs) drawCrosshair(c, w, h)
        // film grain over the digitized era
        if (era == 1) repeat(260) {
            val g = if (fx.nextBoolean()) Color.White else Color.Black
            drawRect(g.copy(alpha = 0.07f), Offset(fx.nextFloat() * w, fx.nextFloat() * h), Size(3f, 3f))
        }
        // era caption + TV static on every switch
        if (eraT < 2f) {
            val a = (if (eraT < 1.5f) 1f else (2f - eraT) / 0.5f) * 0.8f
            drawText(text, Era.entries[era].label, Offset(w * 0.6f, h * 0.9f),
                TextStyle(color = Neon.Yellow.copy(alpha = a), fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = Arcade))
        }
        if (eraT < STATIC_SECONDS) {
            val amt = 1f - eraT / STATIC_SECONDS
            drawRect(Color(0xFF101010).copy(alpha = 0.7f * amt))
            repeat(500) {
                val v = fx.nextFloat()
                drawRect(Color(v, v, v, amt), Offset(fx.nextFloat() * w, fx.nextFloat() * h), Size(w * 0.012f, h * 0.008f))
            }
            repeat(3) {
                drawRect(Color.White.copy(alpha = 0.25f * amt), Offset(0f, fx.nextFloat() * h), Size(w, h * 0.015f))
            }
        }
    }

    private fun DrawScope.drawEnemy(e: Enemy, w: Float, h: Float, aspect: Float) {
        val k = kindOf(e)
        val cell = cellSize(e) * h
        val ctr = center(e)
        val left = ctr.x * w - k.w * cell / 2f
        val top = ctr.y * h - k.h * cell / 2f
        val fog = ((e.z - 1.4f) / 2.8f).coerceIn(0f, 1f) * 0.45f
        if (e.cover < 0) drawOval(Color.Black.copy(alpha = 0.35f), Offset(left, groundY(e.z) * h - cell * 0.5f), Size(k.w * cell, cell))
        when (era) {
            0 -> {
                // voxels: extrude toward the vanishing point (things right of centre show their left side)
                val s = k.sprite!!
                val depth = cell * 0.55f
                val dx = -(ctr.x - 0.5f) * 2f * depth
                val dy = -depth * 0.55f
                for ((x, y, col) in s.cells) drawRect(shade(col, 0.45f, fog), Offset(left + x * cell + dx, top + y * cell + dy), Size(cell, cell))
                for ((x, y, col) in s.cells) drawRect(shade(col, 1f, fog), Offset(left + x * cell, top + y * cell), Size(cell * 0.94f, cell * 0.94f))
            }
            1 -> {
                // digitized actor: chunky pixels (nearest-neighbour), walking or aiming at the player
                val frame = if (aiming(e)) DigitizedActors.FRAME_AIM else ((t * 3.5f + e.phase).toInt() % 2)
                val img = actorFrames[k.actor][frame]
                val dw = k.w * cell; val dh = k.h * cell
                drawImage(img, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(img.width, img.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                    alpha = 1f - fog * 0.6f, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                if (muzzleFlash(e)) {
                    val m = Offset(left + DigitizedActors.MUZZLE_X / DigitizedActors.W * dw, top + DigitizedActors.MUZZLE_Y / DigitizedActors.H * dh)
                    val r = dw * 0.35f
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFD060), Color(0x00FF8000)), m, r), r, m)
                }
            }
            2 -> drawPoly(k, left, top, cell, e.phase, fog)
            else -> drawHd(k, left, top, cell, e.phase, fog)
        }
        if (e.cover >= 0 && era != 1 && muzzleFlash(e)) {   // cover shooters fire back
        val m = Offset(ctr.x * w + k.w * cell * 0.45f, ctr.y * h); val r = k.w * cell * 0.35f
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFFFD060), Color(0x00FF8000)), m, r), r, m)
        }
    }

    private fun DrawScope.drawCover(c: Cover, w: Float, h: Float) {
        val ow = c.w / c.z * h; val oh = c.h / c.z * h
        val cx = c.sx * w; val bottom = groundY(c.z) * h
        val l = cx - ow / 2; val r = cx + ow / 2; val top = bottom - oh
        val fog = ((c.z - 1.4f) / 2.8f).coerceIn(0f, 1f) * 0.45f
        fun f(col: Color) = Color(col.red + (0.12f - col.red) * fog, col.green + (0.08f - col.green) * fog, col.blue + (0.2f - col.blue) * fog, col.alpha)
        fun quad(col: Color, a: Offset, b: Offset, cc: Offset, d: Offset) = fillPoly(f(col), a, b, cc, d)
        // a top face slanting toward the vanishing point gives every box some depth
        val depth = oh * 0.22f
        val dx = -(c.sx - 0.5f) * 2f * depth
        drawOval(Color.Black.copy(alpha = 0.35f), Offset(l - ow * 0.05f, bottom - oh * 0.08f), Size(ow * 1.1f, oh * 0.16f))
        when (c.type) {
            0, 1 -> {   // neon crate / pillar: dark glassy block with glowing edges
                val edge = if (c.type == 0) Neon.Magenta else Neon.Cyan
                quad(Color(0xFF241050), Offset(l, top), Offset(r, top), Offset(r + dx, top - depth), Offset(l + dx, top - depth))
                drawRect(f(Color(0xFF14062E)), Offset(l, top), Size(ow, oh))
                drawRect(f(edge), Offset(l, top), Size(ow, oh), style = Stroke(3f))
                drawLine(f(edge.copy(alpha = 0.5f)), Offset(l, top), Offset(r, bottom), 2f)
                drawLine(f(edge.copy(alpha = 0.5f)), Offset(r, top), Offset(l, bottom), 2f)
                drawLine(f(edge), Offset(l, top), Offset(l + dx, top - depth), 2f)
                drawLine(f(edge), Offset(r, top), Offset(r + dx, top - depth), 2f)
                drawLine(f(edge), Offset(l + dx, top - depth), Offset(r + dx, top - depth), 2f)
            }
            10 -> {     // 1994 sedan, side on
                val body = Color(0xFF6E1E1E)
                val cabTop = top; val beltY = top + oh * 0.42f
                quad(Color(0xFF3A3F48), Offset(l + ow * 0.22f, beltY), Offset(l + ow * 0.3f, cabTop), Offset(l + ow * 0.68f, cabTop), Offset(l + ow * 0.8f, beltY))
                quad(Color(0xFF8AA0B8).copy(alpha = 0.55f), Offset(l + ow * 0.26f, beltY - oh * 0.03f), Offset(l + ow * 0.32f, cabTop + oh * 0.06f),
                    Offset(l + ow * 0.66f, cabTop + oh * 0.06f), Offset(l + ow * 0.76f, beltY - oh * 0.03f))   // windows
                drawLine(f(Color(0xFF3A3F48)), Offset(l + ow * 0.5f, cabTop + oh * 0.05f), Offset(l + ow * 0.5f, beltY), 3f)
                drawRect(Brush.verticalGradient(listOf(f(Color(0xFFA03A30)), f(body), f(Color(0xFF3A0E0E))), beltY, bottom - oh * 0.12f),
                    Offset(l, beltY), Size(ow, bottom - oh * 0.12f - beltY))
                drawLine(f(Color(0xFFCFC8B8)), Offset(l, beltY + oh * 0.2f), Offset(r, beltY + oh * 0.2f), 2f)       // trim
                drawRect(f(Color(0xFFFFE8A0)), Offset(r - ow * 0.04f, beltY + oh * 0.08f), Size(ow * 0.04f, oh * 0.1f))   // headlight
                for (wx in listOf(0.2f, 0.8f)) {
                    val wc = Offset(l + ow * wx, bottom - oh * 0.14f)
                    drawCircle(f(Color(0xFF111111)), oh * 0.16f, wc); drawCircle(f(Color(0xFF888888)), oh * 0.07f, wc)
                }
            }
            11 -> {     // dumpster
                val g = Color(0xFF2E5236)
                quad(Color(0xFF3E6A46), Offset(l, top), Offset(r, top), Offset(r + dx, top - depth), Offset(l + dx, top - depth))
                drawRect(Brush.horizontalGradient(listOf(f(Color(0xFF477A50)), f(g), f(Color(0xFF1A3020))), l, r), Offset(l, top), Size(ow, oh))
                for (i in 1..3) drawLine(f(Color(0xFF1A3020)), Offset(l + ow * i / 4f, top), Offset(l + ow * i / 4f, bottom), 2f)
                drawRect(f(Color(0xFF7A4A28).copy(alpha = 0.6f)), Offset(l + ow * 0.1f, top + oh * 0.6f), Size(ow * 0.3f, oh * 0.15f))   // rust
                drawRect(f(Color(0xFF1A3020)), Offset(l - ow * 0.03f, top - oh * 0.04f), Size(ow * 1.06f, oh * 0.08f))                  // lid lip
            }
            12 -> {     // oil drums
                for (i in 0 until 2) {
                    val dl = l + i * ow * 0.5f; val dw = ow * 0.48f
                    drawRect(Brush.horizontalGradient(listOf(f(Color(0xFFD07030)), f(Color(0xFF9A4A1E)), f(Color(0xFF4A2410))), dl, dl + dw),
                        Offset(dl, top), Size(dw, oh))
                    for (rb in listOf(0.3f, 0.7f)) drawLine(f(Color(0xFF3A1A0A)), Offset(dl, top + oh * rb), Offset(dl + dw, top + oh * rb), 2.5f)
                    drawOval(f(Color(0xFF5A2A12)), Offset(dl, top - oh * 0.06f), Size(dw, oh * 0.12f))
                }
            }
            20 -> {     // flat-shaded wooden crate
                val side = ow * 0.22f * (if (c.sx > 0.5f) -1f else 1f)
                quad(Color(0xFFB08850), Offset(l, top), Offset(r, top), Offset(r + dx, top - depth), Offset(l + dx, top - depth))
                quad(Color(0xFF6A4E2A), if (side < 0) Offset(l, top) else Offset(r, top), if (side < 0) Offset(l + dx, top - depth) else Offset(r + dx, top - depth),
                    if (side < 0) Offset(l + dx, bottom - depth) else Offset(r + dx, bottom - depth), if (side < 0) Offset(l, bottom) else Offset(r, bottom))
                drawRect(f(Color(0xFF8A6A3A)), Offset(l, top), Size(ow, oh))
                drawRect(f(Color(0xFF5A4022)), Offset(l, top), Size(ow, oh), style = Stroke(3f))
                drawLine(f(Color(0xFF5A4022)), Offset(l, top), Offset(r, bottom), 3f)
                drawLine(f(Color(0xFF5A4022)), Offset(l, top + oh / 2), Offset(r, top + oh / 2), 2f)
            }
            21 -> {     // steel drums, low-poly
                for (i in 0 until 2) {
                    val dl = l + i * ow * 0.5f; val dw = ow * 0.48f
                    quad(Color(0xFF5A7090), Offset(dl, top), Offset(dl + dw * 0.5f, top), Offset(dl + dw * 0.5f, bottom), Offset(dl, bottom))
                    quad(Color(0xFF3A4A62), Offset(dl + dw * 0.5f, top), Offset(dl + dw, top), Offset(dl + dw, bottom), Offset(dl + dw * 0.5f, bottom))
                    quad(Color(0xFF7A90B0), Offset(dl, top), Offset(dl + dw, top), Offset(dl + dw * 0.85f, top - oh * 0.07f), Offset(dl + dw * 0.15f, top - oh * 0.07f))
                    drawLine(f(Color(0xFFD8B020)), Offset(dl, top + oh * 0.5f), Offset(dl + dw, top + oh * 0.5f), 3f)
                }
            }
            30 -> {     // concrete jersey barrier, smooth gradient
                val path = Path().apply {
                    moveTo(l, bottom); lineTo(l + ow * 0.06f, top + oh * 0.55f); lineTo(l + ow * 0.18f, top)
                    lineTo(r - ow * 0.18f, top); lineTo(r - ow * 0.06f, top + oh * 0.55f); lineTo(r, bottom); close()
                }
                drawPath(path, Brush.verticalGradient(listOf(f(Color(0xFFB8B8B0)), f(Color(0xFF7A7A74)), f(Color(0xFF3A3A38))), top, bottom))
                drawRect(f(Color(0xFFE8A020).copy(alpha = 0.8f)), Offset(l + ow * 0.2f, top + oh * 0.25f), Size(ow * 0.6f, oh * 0.08f))
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent), top, top + oh * 0.2f),
                    Offset(l + ow * 0.18f, top), Size(ow * 0.64f, oh * 0.2f))   // wet sheen
            }
            31 -> {     // rooftop AC unit with a fan grille
                quad(Color(0xFF8A9098), Offset(l, top), Offset(r, top), Offset(r + dx, top - depth), Offset(l + dx, top - depth))
                drawRect(Brush.horizontalGradient(listOf(f(Color(0xFF9AA0A8)), f(Color(0xFF5A6068)), f(Color(0xFF2A2E34))), l, r), Offset(l, top), Size(ow, oh))
                val fc = Offset(cx, top + oh * 0.5f); val fr = oh * 0.34f
                drawCircle(f(Color(0xFF1A1D22)), fr, fc)
                rotate(t * 400f, fc) { for (i in 0 until 3) { val a = i * 2.094f
                    drawLine(f(Color(0xFF4A5058)), fc, Offset(fc.x + cos(a) * fr * 0.9f, fc.y + sin(a) * fr * 0.9f), 4f) } }
                drawCircle(f(Color(0xFF6A7078)), fr, fc, style = Stroke(2f))
            }
            32 -> {     // sandbag wall
                val rows = 3; val per = 4
                for (ry in 0 until rows) for (i in 0 until per - (ry % 2)) {
                    val bw = ow / per; val bh = oh / rows
                    val bx = l + i * bw + (ry % 2) * bw / 2; val by = bottom - (ry + 1) * bh
                    drawOval(Brush.verticalGradient(listOf(f(Color(0xFFC8B488)), f(Color(0xFF8A7650)), f(Color(0xFF4A3E28))), by, by + bh),
                        Offset(bx, by), Size(bw * 1.05f, bh * 1.1f))
                }
            }
        }
    }

    /** Drawn above the dimming scrim: the RetroSight logo zooms in during every 1987 stretch. */
    fun drawOverlay(ds: DrawScope) = with(ds) {
        if (t < 8f || era != 0) return@with           // not over the start-up splash
        val lt = eraT
        if (lt > ERA_SECONDS - 0.2f) return@with
        val zoom = if (lt < 0.35f) 1f + 2f * (1f - lt / 0.35f).let { it * it } else 1f
        val a = (lt / 0.2f).coerceIn(0f, 1f) * ((ERA_SECONDS - 0.2f - lt) / 0.4f).coerceIn(0f, 1f)
        val shine = ((lt - 0.4f) / 1.3f).let { if (it in 0f..1.1f) it else -1f }
        drawRetroSightLogo(Offset(size.width * 0.7f, size.height * 0.2f), size.width * 0.5f * zoom, shine, a * 0.95f, subtitle = false)
    }

    private fun DrawScope.drawCrosshair(c: Crosshair, w: Float, h: Float) {
        val cx = c.x * w; val cy = c.y * h
        val locked = c.dwell > 0.1f
        val r = h * (if (locked) 0.045f - 0.02f * (c.dwell / 0.15f).coerceAtMost(1f) else 0.05f)
        val col = if (locked) Neon.Yellow else c.color
        when (era) {
            3 -> {   // holographic sight: thin ring, glow halo, centre dot
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.25f), Color.Transparent), Offset(cx, cy), r * 1.8f), r * 1.8f, Offset(cx, cy))
                drawCircle(col.copy(alpha = 0.9f), r * 0.9f, Offset(cx, cy), style = Stroke(2f))
                for (i in 0 until 4) {
                    val a = i * 1.5708f + 0.785f
                    drawLine(col, Offset(cx + cos(a) * r * 0.9f, cy + sin(a) * r * 0.9f), Offset(cx + cos(a) * r * 1.25f, cy + sin(a) * r * 1.25f), 2f)
                }
                drawCircle(Color.White, 3f, Offset(cx, cy))
            }
            2 -> {   // thin modern reticle: open brackets, no spin
                val s = r * 1.1f
                for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                    drawLine(col, Offset(cx + dx * s, cy + dy * s), Offset(cx + dx * s * 0.45f, cy + dy * s), 3f)
                    drawLine(col, Offset(cx + dx * s, cy + dy * s), Offset(cx + dx * s, cy + dy * s * 0.45f), 3f)
                }
                drawCircle(col, 4f, Offset(cx, cy))
            }
            else -> {
                rotate(if (era == 0) c.spin * 57.3f else 0f, Offset(cx, cy)) {
                    drawCircle(col, r, Offset(cx, cy), style = Stroke(4f))
                    for (i in 0 until 4) {
                        val a = i * 1.5708f
                        drawLine(col, Offset(cx + cos(a) * r * 0.55f, cy + sin(a) * r * 0.55f),
                            Offset(cx + cos(a) * r * 1.45f, cy + sin(a) * r * 1.45f), 4f)
                    }
                }
                drawRect(col, Offset(cx - 3f, cy - 3f), Size(6f, 6f))
            }
        }
        if (c.flash > 0f) {
            drawCircle(Color.White.copy(alpha = c.flash * 0.8f), r * (2.5f - c.flash), Offset(cx, cy), style = Stroke(6f))
            drawCircle(Color.White.copy(alpha = c.flash * 0.35f), r * 0.8f, Offset(cx, cy))
        }
    }

    /** Low-poly figures in a 10 x h unit box; light comes from the left (left faces bright). */
    private fun DrawScope.drawPoly(k: Kind, left: Float, top: Float, u: Float, phase: Float, fog: Float) {
        val (lite, mid, dark, accent) = k.colors.map { shade(it, 1f, fog) }
        fun p(x: Float, y: Float) = Offset(left + x * u, top + y * u)
        fun poly(c: Color, a: Offset, b: Offset, cc: Offset, d: Offset? = null) = fillPoly(c, a, b, cc, d)
        val sway = sin(t * 4f + phase) * 0.6f
        when (k.poly) {
            0, 1 -> {
                val wide = if (k.poly == 1) 0.8f else 0f
                // legs
                poly(dark, p(3f, 10f), p(4.6f, 10f), p(4.4f + sway, 16f), p(2.8f + sway, 16f))
                poly(mid, p(5.4f, 10f), p(7f, 10f), p(7.2f - sway, 16f), p(5.6f - sway, 16f))
                // torso: lit left half, shaded right half
                poly(lite, p(2.5f - wide, 5f), p(5f, 5f), p(5f, 10.5f), p(3f, 10.5f))
                poly(mid, p(5f, 5f), p(7.5f + wide, 5f), p(7f, 10.5f), p(5f, 10.5f))
                if (k.poly == 1) {  // shoulder plates on the heavy
                    poly(lite, p(1f, 4.6f), p(3.2f, 4.6f), p(3f, 6.2f), p(1.2f, 6.2f))
                    poly(dark, p(6.8f, 4.6f), p(9f, 4.6f), p(8.8f, 6.2f), p(7f, 6.2f))
                }
                // arms: left hangs, right holds the gun out
                poly(mid, p(2.2f - wide, 5.3f), p(3.2f - wide, 5.3f), p(2.6f, 9.6f), p(1.6f, 9.4f))
                poly(dark, p(7f + wide, 5.3f), p(8f + wide, 5.6f), p(9.2f, 8.2f), p(8.4f, 8.6f))
                poly(Color(0xFF1A1C20), p(8.3f, 7.6f), p(10f, 7.6f), p(10f, 8.4f), p(8.3f, 8.4f))
                // head: hexagon split light/dark, visor
                poly(lite, p(5f, 1.2f), p(3.4f, 2.1f), p(3.4f, 3.9f), p(5f, 4.8f))
                poly(dark, p(5f, 1.2f), p(6.6f, 2.1f), p(6.6f, 3.9f), p(5f, 4.8f))
                poly(accent, p(3.6f, 2.6f), p(6.4f, 2.6f), p(6.2f, 3.3f), p(3.8f, 3.3f))
            }
            else -> {  // hover drone: diamond hull, two rotor discs, red eye
                val spin = sin(t * 30f) * 0.5f + 0.5f
                drawOval(lite.copy(alpha = 0.5f), p(0f, 0.3f), Size(3.5f * u, (0.6f + 0.4f * spin) * u))
                drawOval(lite.copy(alpha = 0.5f), p(6.5f, 0.3f), Size(3.5f * u, (0.6f + 0.4f * spin) * u))
                poly(lite, p(5f, 1f), p(1.5f, 3f), p(5f, 5.5f))
                poly(dark, p(5f, 1f), p(8.5f, 3f), p(5f, 5.5f))
                poly(mid, p(1.5f, 3f), p(8.5f, 3f), p(5f, 4.2f))
                drawCircle(accent, 0.6f * u, p(5f, 3.4f))
            }
        }
    }

    // ------------------------------------------------------------------ 2006 HD: rainy rooftop at night
    private class Bokeh(val x: Float, val y: Float, val r: Float, val color: Color, val ph: Float)

    private val bokeh = Random(9).let { r ->
        val cols = listOf(Color(0xFFFFB050), Color(0xFFFFE0A0), Color(0xFF60C8FF), Color(0xFFFF5070), Color(0xFFFFFFFF))
        List(34) { Bokeh(r.nextFloat(), 0.25f + r.nextFloat() * 0.3f, 0.015f + r.nextFloat() * 0.04f, cols.random(r), r.nextFloat() * 6.28f) }
    }
    private val hdTowers = Random(13).let { r -> List(12) { Building(it / 12f, 0.05f + r.nextFloat() * 0.06f, 0.1f + r.nextFloat() * 0.3f, r.nextInt()) } }
    private val rain = Random(17).let { r -> List(110) { Triple(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f) } }

    private fun rooftopBackdrop(ds: DrawScope) = with(ds) {
        val w = size.width; val h = size.height
        val hz = h * horizon
        // night sky with a warm city glow on the horizon
        drawRect(Brush.verticalGradient(listOf(Color(0xFF04060C), Color(0xFF0E1528), Color(0xFF2A2440)), 0f, hz), size = Size(w, hz))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFF9A50).copy(alpha = 0.35f), Color.Transparent), Offset(w * 0.5f, hz), w * 0.6f),
            w * 0.6f, Offset(w * 0.5f, hz))
        // searchlights sweeping the clouds
        for (i in 0 until 2) {
            val base = Offset(w * (0.25f + i * 0.5f), hz)
            val ang = -1.5708f + sin(t * 0.4f + i * 2.1f) * 0.6f
            val len = h * 0.9f; val spread = 0.06f
            val tip1 = Offset(base.x + cos(ang - spread) * len, base.y + sin(ang - spread) * len)
            val tip2 = Offset(base.x + cos(ang + spread) * len, base.y + sin(ang + spread) * len)
            drawPath(Path().apply { moveTo(base.x, base.y); lineTo(tip1.x, tip1.y); lineTo(tip2.x, tip2.y); close() },
                Brush.radialGradient(listOf(Color(0xFFCFE8FF).copy(alpha = 0.22f), Color.Transparent), base, len))
        }
        // out-of-focus skyline: soft towers + bokeh (depth of field)
        for (b in hdTowers) {
            val left = b.x * w; val bw = b.width * w; val top = hz - b.height * h
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1A2236), Color(0xFF0C1020)), top, hz), Offset(left, top), Size(bw, hz - top))
            drawRect(Color(0xFFFF3040).copy(alpha = 0.3f + 0.7f * sin(t * 3f + b.seed).coerceAtLeast(0f)),
                Offset(left + bw * 0.45f, top - h * 0.01f), Size(bw * 0.1f, h * 0.008f))   // aircraft warning light
        }
        for (b in bokeh) {
            val c = Offset(b.x * w, b.y * h + sin(t * 0.3f + b.ph) * h * 0.004f)
            if (c.y > hz) continue
            val r = b.r * h; val a = 0.25f + 0.15f * sin(t * 1.3f + b.ph)
            drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = a), b.color.copy(alpha = a * 0.7f), Color.Transparent), c, r), r, c)
        }
        // rooftop parapet
        drawRect(Brush.verticalGradient(listOf(Color(0xFF3A3E46), Color(0xFF1E2126)), hz - h * 0.02f, hz + h * 0.01f),
            Offset(0f, hz - h * 0.02f), Size(w, h * 0.03f))
        // wet concrete with the city reflected in it
        val floorTop = hz + h * 0.01f
        drawRect(Brush.verticalGradient(listOf(Color(0xFF15181E), Color(0xFF262A31), Color(0xFF101216)), floorTop, h),
            Offset(0f, floorTop), Size(w, h - floorTop))
        for (b in bokeh) {
            val ry = floorTop + (hz - b.y * h) * 0.9f
            if (ry > h) continue
            val r = b.r * h
            drawOval(Brush.radialGradient(listOf(b.color.copy(alpha = 0.18f), Color.Transparent), Offset(b.x * w, ry), r * 1.6f),
                Offset(b.x * w - r * 0.6f, ry - r * 1.6f), Size(r * 1.2f, r * 3.2f))
        }
        // helipad marking in perspective
        val pc = Offset(w * 0.5f, hz + (h - hz) * 0.62f)
        drawOval(Color(0xFFE8E0C0).copy(alpha = 0.18f), Offset(pc.x - w * 0.22f, pc.y - h * 0.09f), Size(w * 0.44f, h * 0.18f), style = Stroke(5f))
        // rain
        for ((rx, ry, sp) in rain) {
            val y = ((ry + t * sp) % 1f) * h
            val x = ((rx + t * sp * 0.08f) % 1f) * w
            drawLine(Color(0xFFBFD8FF).copy(alpha = 0.22f), Offset(x, y), Offset(x - h * 0.006f, y + h * 0.04f * sp), 1.5f)
        }
    }

    /** Smooth-shaded figures with rim light and glowing optics (2006 HD). Box is 10 x h units. */
    private fun DrawScope.drawHd(k: Kind, left: Float, top: Float, u: Float, phase: Float, fog: Float) {
        val (body, deep, rim, glow) = k.colors
        val a = 1f - fog * 0.9f    // depth of field: far ones fade into the haze
        fun p(x: Float, y: Float) = Offset(left + x * u, top + y * u)
        fun cr(r: Float) = androidx.compose.ui.geometry.CornerRadius(r * u, r * u)
        fun shaded(x: Float, y: Float, w: Float, h: Float, round: Float) {
            // gradient from the rim-lit left edge to deep shadow on the right
            drawRoundRect(Brush.horizontalGradient(listOf(rim.copy(alpha = 0.9f * a), body.copy(alpha = a), deep.copy(alpha = a)),
                left + x * u, left + (x + w) * u), p(x, y), Size(w * u, h * u), cr(round))
        }
        val sway = sin(t * 4f + phase) * 0.5f
        when (k.poly) {
            10, 11 -> {
                shaded(3f + sway * 0.3f, 10f, 1.7f, 6f, 0.7f)            // legs
                shaded(5.3f - sway * 0.3f, 10f, 1.7f, 6f, 0.7f)
                shaded(2.4f, 4.8f, 5.2f, 6.2f, 1.4f)                      // torso
                shaded(1.3f, 5f, 1.4f, 4.6f, 0.7f)                        // arms
                shaded(7.3f, 5f, 1.4f, 3.2f, 0.7f)
                drawRoundRect(deep.copy(alpha = a), p(7.6f, 7.4f), Size(2.4f * u, 0.8f * u), cr(0.2f))   // weapon
                if (k.poly == 11) shaded(2f, 4.4f, 6f, 1.4f, 0.7f)         // collar plate
                // head with a specular highlight
                val hc = p(5f, 2.8f); val hr = 1.8f * u
                drawCircle(Brush.radialGradient(listOf(rim.copy(alpha = a), body.copy(alpha = a), deep.copy(alpha = a)),
                    Offset(hc.x - hr * 0.5f, hc.y - hr * 0.5f), hr * 2f), hr, hc)
                // glowing visor / optics
                val vc = p(5f, 3f)
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.6f * a), Color.Transparent), vc, 3f * u), 3f * u, vc)
                if (k.poly == 10) drawRoundRect(glow.copy(alpha = a), p(3.6f, 2.6f), Size(2.8f * u, 0.7f * u), cr(0.35f))
                else { drawCircle(glow.copy(alpha = a), 0.45f * u, p(4.2f, 2.9f)); drawCircle(glow.copy(alpha = a), 0.45f * u, p(5.8f, 2.9f)) }
            }
            else -> {   // drone: smooth hull, spinning rotor blur, red eye glow
                val blur = 0.5f + 0.5f * sin(t * 40f)
                drawOval(rim.copy(alpha = 0.25f * a), p(-0.5f, 0f), Size(4f * u, (0.5f + 0.4f * blur) * u))
                drawOval(rim.copy(alpha = 0.25f * a), p(6.5f, 0f), Size(4f * u, (0.5f + 0.4f * blur) * u))
                drawOval(Brush.verticalGradient(listOf(rim.copy(alpha = a), body.copy(alpha = a), deep.copy(alpha = a)), top + 1f * u, top + 4.5f * u),
                    p(1.5f, 1f), Size(7f * u, 3.5f * u))
                val ec = p(5f, 3f)
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.7f * a), Color.Transparent), ec, 2.2f * u), 2.2f * u, ec)
                drawCircle(Color.White.copy(alpha = a), 0.3f * u, ec)
            }
        }
    }

    private companion object {
        const val MIN_X = 0.6f
        const val MAX_X = 0.95f
        const val ERA_SECONDS = 5f
        const val STATIC_SECONDS = 0.35f
    }

    private fun shade(c: Color, k: Float, fog: Float): Color {
        val r = c.red * k; val g = c.green * k; val b = c.blue * k
        // fade toward the haze with distance
        return Color(r + (0.16f - r) * fog, g + (0.05f - g) * fog, b + (0.30f - b) * fog, 0.95f)
    }

    /** Muted, warm "photo under street lights" tone for the digitized era. */
    private fun dusk(c: Color, k: Float, fog: Float): Color {
        val r = (c.red * k * 1.05f).coerceAtMost(1f); val g = (c.green * k * 0.95f).coerceAtMost(1f); val b = (c.blue * k * 0.85f).coerceAtMost(1f)
        return Color(r + (0.25f - r) * fog, g + (0.16f - g) * fog, b + (0.18f - b) * fog)
    }
}

private fun DrawScope.fillPoly(c: Color, a: Offset, b: Offset, cc: Offset, d: Offset?) = drawPath(Path().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(cc.x, cc.y); if (d != null) lineTo(d.x, d.y); close()
}, c)
