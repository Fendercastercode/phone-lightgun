package dev.lightgun.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/**
 * Chunky 90s-arcade pixel lettering drawn from an original 5x7 bitmap font: bold blocks, a
 * chrome/fire gradient across the letters, a dark 3D extrusion, an outline, an italic lean and
 * an optional shine sweep. No font files needed.
 */
object PixelFont {
    const val ROWS = 7
    const val ADVANCE = 7          // 5 px glyph + 1 px bold + 1 px gap

    private val glyphs: Map<Char, List<String>> = mapOf(
        'A' to listOf(".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'B' to listOf("####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."),
        'C' to listOf(".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."),
        'D' to listOf("####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."),
        'E' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#####"),
        'F' to listOf("#####", "#....", "#....", "####.", "#....", "#....", "#...."),
        'G' to listOf(".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".####"),
        'H' to listOf("#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"),
        'I' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"),
        'J' to listOf("..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."),
        'K' to listOf("#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"),
        'L' to listOf("#....", "#....", "#....", "#....", "#....", "#....", "#####"),
        'M' to listOf("#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"),
        'N' to listOf("#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"),
        'O' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'P' to listOf("####.", "#...#", "#...#", "####.", "#....", "#....", "#...."),
        'Q' to listOf(".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"),
        'R' to listOf("####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"),
        'S' to listOf(".####", "#....", "#....", ".###.", "....#", "....#", "####."),
        'T' to listOf("#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."),
        'U' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        'V' to listOf("#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."),
        'W' to listOf("#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"),
        'X' to listOf("#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"),
        'Y' to listOf("#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."),
        'Z' to listOf("#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf(".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."),
        '!' to listOf("..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."),
        '-' to listOf(".....", ".....", ".....", ".###.", ".....", ".....", "....."),
        '.' to listOf(".....", ".....", ".....", ".....", ".....", ".....", "..#.."),
    )

    /** Lit pixel cells of [text] (x in pixel columns, y in rows), with the bold column added. */
    fun cells(text: String, bold: Boolean = true): List<Pair<Int, Int>> = buildList {
        text.uppercase().forEachIndexed { i, ch ->
            val g = glyphs[ch] ?: return@forEachIndexed
            fun on(x: Int, y: Int) = y in g.indices && x in 0 until g[y].length && g[y][x] == '#'
            fun stem(x: Int, y: Int) = on(x, y - 1) || on(x, y + 1)
            g.forEachIndexed { y, row -> row.forEachIndexed { x, c ->
                if (c != '#') return@forEachIndexed
                add(i * ADVANCE + x to y)
                // thicken straight strokes only, so diagonals (N, M, Z...) stay crisp
                val straight = stem(x, y) || (on(x - 1, y) && !stem(x - 1, y)) || (on(x + 1, y) && !stem(x + 1, y))
                if (bold && straight) add(i * ADVANCE + x + 1 to y)
            } }
        }
    }.distinct()

    fun width(text: String) = text.length * ADVANCE - 1

    val chrome = listOf(Color.White, Color(0xFFBFF6FF), Color(0xFF3FA9FF), Color(0xFF1A2A9E), Color(0xFF7FE0FF), Color.White)
    val fire = listOf(Color(0xFFFFFFC0), Color(0xFFFFE600), Color(0xFFFF9A00), Color(0xFFE02060), Color(0xFFFF7A00), Color(0xFFFFE070))
    val neon = listOf(Neon.Cyan, Neon.Cyan, Neon.White, Neon.Cyan, Neon.Cyan, Neon.Cyan)

    fun gradientAt(palette: List<Color>, f: Float): Color {
        val p = f.coerceIn(0f, 1f) * (palette.size - 1)
        val i = p.toInt().coerceAtMost(palette.size - 2)
        return lerp(palette[i], palette[i + 1], p - i)
    }
}

/**
 * Draw [text] in the arcade pixel font with its top-left at [topLeft], one font pixel = [px].
 * [shine] in 0..1 sweeps a highlight across (negative = none). [lean] slants it like an italic logo.
 */
fun DrawScope.drawArcadeText(text: String, topLeft: Offset, px: Float, palette: List<Color> = PixelFont.chrome,
                             depth: Color = Color(0xFF3A0A5A), outline: Color = Color(0xFF07021A),
                             shine: Float = -1f, alpha: Float = 1f, lean: Float = 0.28f, extrude: Int = 3,
                             startColumn: Int = 0, totalColumns: Int = PixelFont.width(text)) {
    if (alpha <= 0f) return
    val cells = PixelFont.cells(text)
    fun at(x: Int, y: Int) = Offset(topLeft.x + x * px + (PixelFont.ROWS - 1 - y) * px * lean, topLeft.y + y * px)
    val o = px * 0.45f
    // outline (behind everything, around the face and the extrusion)
    for ((x, y) in cells) {
        val p = at(x, y)
        drawRect(outline.copy(alpha = alpha), Offset(p.x - o, p.y - o), Size(px + 2 * o + extrude * px * 0.5f, px + 2 * o + extrude * px * 0.5f))
    }
    // 3D extrusion down-right
    for (d in extrude downTo 1) for ((x, y) in cells) {
        val p = at(x, y)
        val k = 1f - d / (extrude + 1f) * 0.5f
        drawRect(Color(depth.red * k, depth.green * k, depth.blue * k, alpha), Offset(p.x + d * px * 0.5f, p.y + d * px * 0.5f), Size(px, px))
    }
    // face: vertical metallic gradient, a faint pixel bevel, and the shine band
    for ((x, y) in cells) {
        val p = at(x, y)
        val c = PixelFont.gradientAt(palette, (y + 0.5f) / PixelFont.ROWS)
        drawRect(c.copy(alpha = alpha), p, Size(px, px))
        drawRect(Color.White.copy(alpha = 0.25f * alpha), p, Size(px, px * 0.18f))
        if (shine >= 0f) {
            val col = (startColumn + x + (PixelFont.ROWS - 1 - y) * 0.6f) / (totalColumns + 6f)
            val d = kotlin.math.abs(col - shine)
            if (d < 0.05f) drawRect(Color.White.copy(alpha = (1f - d / 0.05f) * 0.85f * alpha), p, Size(px, px))
        }
    }
}

/**
 * A crosshair reticle that fills a whole letter cell in a word drawn by [drawArcadeText] - the
 * same width/height as any other letter, standing in for it entirely rather than sitting as a
 * small decoration on top of it.
 */
private fun DrawScope.drawLetterCrosshair(topLeft: Offset, px: Float, lean: Float, letterIndex: Int,
                                           color: Color, alpha: Float) {
    val cx = letterIndex * PixelFont.ADVANCE + 2f   // centre column of a 5-wide glyph
    val cy = (PixelFont.ROWS - 1) / 2f              // centre row
    val lean0 = (PixelFont.ROWS - 1 - cy) * px * lean
    val center = Offset(topLeft.x + cx * px + lean0 + px * 0.5f, topLeft.y + cy * px + px * 0.5f)
    val rx = px * 2.5f   // half the glyph cell's ~5 px width
    val ry = px * 3.5f   // half the glyph cell's 7 px height
    val r = minOf(rx, ry) * 0.72f
    val sw = px * 0.9f   // as bold as the rest of the lettering
    drawCircle(color.copy(alpha = alpha), radius = r, center = center, style = Stroke(width = sw))
    drawLine(color.copy(alpha = alpha), Offset(center.x - rx, center.y), Offset(center.x + rx, center.y), strokeWidth = sw)
    drawLine(color.copy(alpha = alpha), Offset(center.x, center.y - ry), Offset(center.x, center.y + ry), strokeWidth = sw)
}

/** The RetroSight logo: chrome RETR + a full-size crosshair reticle standing in for the O + fire
 *  SIGHT, sized to [width] px, centred at [center]. */
fun DrawScope.drawRetroSightLogo(center: Offset, width: Float, shine: Float = -1f, alpha: Float = 1f, subtitle: Boolean = true) {
    val total = PixelFont.width("RETRO") + 3 + PixelFont.width("SIGHT")
    val px = width / (total + PixelFont.ROWS * 0.28f + 2f)
    val h = PixelFont.ROWS * px
    val left = center.x - width / 2f
    val top = center.y - h / 2f - (if (subtitle) px * 2.5f else 0f)
    drawArcadeText("RETR", Offset(left, top), px, PixelFont.chrome, shine = shine, alpha = alpha,
        startColumn = 0, totalColumns = total)
    drawLetterCrosshair(Offset(left, top), px, 0.28f, letterIndex = 4, color = Color(0xFFFF2244), alpha = alpha)
    val zx = left + (PixelFont.width("RETRO") + 3) * px
    drawArcadeText("SIGHT", Offset(zx, top), px, PixelFont.fire, depth = Color(0xFF5A0A10), shine = shine, alpha = alpha,
        startColumn = PixelFont.width("RETRO") + 3, totalColumns = total)
    if (subtitle) {
        val sub = "LIGHT GUN FOR ANY DISPLAY"
        val spx = px * 0.42f
        val sw = PixelFont.width(sub) * spx
        drawArcadeText(sub, Offset(center.x - sw / 2f, top + h + px * 2.2f), spx, PixelFont.neon, depth = Color(0xFF003A50),
            alpha = alpha, lean = 0f, extrude = 1)
    }
}

@Composable
fun RetroSightLogo(modifier: Modifier = Modifier, shine: Float = -1f, alpha: Float = 1f) {
    Canvas(modifier.fillMaxWidth().aspectRatio(5.2f)) {
        drawRetroSightLogo(Offset(size.width / 2f, size.height / 2f), size.width * 0.96f, shine, alpha)
    }
}
