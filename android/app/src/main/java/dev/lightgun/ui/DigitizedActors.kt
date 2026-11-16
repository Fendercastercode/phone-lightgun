package dev.lightgun.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * "Digitized actor" sprites for the 1994 attract era: each character is painted with smooth,
 * photo-like light and shadow into a small bitmap, then posterized and grained, so when it is
 * scaled up with nearest-neighbour filtering it reads like a filmed actor turned into pixels.
 * All characters are original.
 */
object DigitizedActors {
    const val W = 48
    const val H = 96

    /** Frames per actor: 0, 1 = walking, 2 = aiming at the player. */
    const val FRAME_AIM = 2

    /** Where the gun muzzle is in the aiming frame (bitmap pixels). */
    const val MUZZLE_X = 24f
    const val MUZZLE_Y = 33f

    private data class Look(
        val skin: Int, val hair: Int, val top: Int, val pants: Int, val shoes: Int = 0xFF151515.toInt(),
        val shirt: Int? = null, val tie: Int? = null, val hat: Int? = null, val mask: Int? = null,
        val shades: Boolean = false, val longCoat: Boolean = false, val seed: Int,
    )

    private val looks = listOf(
        // trench coat + fedora
        Look(skin = 0xFFC8916A.toInt(), hair = 0xFF2A1E16.toInt(), top = 0xFF7A6448.toInt(), pants = 0xFF2E333D.toInt(),
            shirt = 0xFFD8D4C8.toInt(), hat = 0xFF3A2E24.toInt(), longCoat = true, seed = 1),
        // ski-mask robber in a bomber jacket
        Look(skin = 0xFFE0B48C.toInt(), hair = 0xFF111111.toInt(), top = 0xFF3B4250.toInt(), pants = 0xFF3C5A82.toInt(),
            mask = 0xFF1E1E22.toInt(), seed = 2),
        // henchman: black suit, red tie, shades
        Look(skin = 0xFF8C5A3C.toInt(), hair = 0xFF0E0B0A.toInt(), top = 0xFF24272E.toInt(), pants = 0xFF24272E.toInt(),
            shirt = 0xFFE6E6E6.toInt(), tie = 0xFF9E2222.toInt(), shades = true, seed = 3),
        // biker: leather vest over a white tee, bandana
        Look(skin = 0xFFD9A47E.toInt(), hair = 0xFF5A3A22.toInt(), top = 0xFF2A2420.toInt(), pants = 0xFF3A4660.toInt(),
            shirt = 0xFFCFCBC0.toInt(), hat = 0xFF8E1E1E.toInt(), seed = 4),
    )

    val count get() = looks.size

    /** [actor][frame] bitmaps. Built once (a few ms). */
    fun build(): List<List<ImageBitmap>> = looks.map { look -> (0..2).map { f -> paint(look, f).asImageBitmap() } }

    // --------------------------------------------------------------------------------------------
    private fun shade(c: Int, k: Float): Int {
        val r = ((c shr 16 and 255) * k).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 255) * k).toInt().coerceIn(0, 255)
        val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Horizontal light-to-shadow fill across [x0, x1] (key light from the upper left). */
    private fun lit(c: Int, x0: Float, x1: Float, hi: Float = 1.35f, lo: Float = 0.45f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(x0, 0f, x1, 0f, intArrayOf(shade(c, hi), c, shade(c, lo)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    }

    private fun solid(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    private fun limb(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w0: Float, w1: Float, p: Paint) {
        // tapered limb as a quad with round ends
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(0.01f)
        val nx = -dy / len; val ny = dx / len
        val path = Path().apply {
            moveTo(x0 + nx * w0 / 2, y0 + ny * w0 / 2); lineTo(x1 + nx * w1 / 2, y1 + ny * w1 / 2)
            lineTo(x1 - nx * w1 / 2, y1 - ny * w1 / 2); lineTo(x0 - nx * w0 / 2, y0 - ny * w0 / 2); close()
        }
        cv.drawPath(path, p)
        cv.drawCircle(x0, y0, w0 / 2, p); cv.drawCircle(x1, y1, w1 / 2, p)
    }

    private fun paint(l: Look, frame: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val aim = frame == FRAME_AIM
        val step = when (frame) { 0 -> 2.5f; 1 -> -2.5f; else -> 0f }
        val skinP = lit(l.skin, 16f, 32f, 1.25f, 0.6f)

        // ---- legs + shoes
        val pantsL = lit(l.pants, 15f, 33f)
        limb(cv, 20f, 52f, 19f - step, 89f, 7.5f, 6f, pantsL)
        limb(cv, 28f, 52f, 29f + step, 89f, 7.5f, 6f, pantsL)
        cv.drawOval(RectF(15f - step, 87f, 23f - step, 93f), lit(l.shoes, 15f, 23f))
        cv.drawOval(RectF(25f + step, 87f, 33f + step, 93f), lit(l.shoes, 25f, 33f))

        // ---- torso (long coats hang to the knees, split in the middle)
        val topP = lit(l.top, 11f, 37f)
        val torso = Path().apply {
            moveTo(12f, 25f); quadTo(24f, 20f, 36f, 25f)
            lineTo(34f, if (l.longCoat) 72f else 54f)
            lineTo(14f, if (l.longCoat) 72f else 54f); close()
        }
        cv.drawPath(torso, topP)
        if (l.longCoat) cv.drawLine(24f, 46f, 24f + step * 0.3f, 72f, solid(shade(l.top, 0.35f)).apply { strokeWidth = 1.2f })
        // shirt / lapels
        l.shirt?.let { sh ->
            cv.drawPath(Path().apply { moveTo(20f, 23f); lineTo(28f, 23f); lineTo(24f, if (l.tie != null) 40f else 34f); close() },
                lit(sh, 20f, 28f, 1.1f, 0.75f))
            l.tie?.let { tie -> cv.drawPath(Path().apply { moveTo(23f, 24f); lineTo(25f, 24f); lineTo(25.6f, 37f); lineTo(24f, 39f); lineTo(22.4f, 37f); close() }, solid(tie)) }
            // lapel edges
            val edge = solid(shade(l.top, 0.3f)).apply { strokeWidth = 0.9f; style = Paint.Style.STROKE }
            cv.drawLine(20f, 23f, 24f, 38f, edge); cv.drawLine(28f, 23f, 24f, 38f, edge)
        }
        // fabric folds
        val fold = solid(shade(l.top, 0.55f)).apply { strokeWidth = 0.8f; alpha = 160 }
        cv.drawLine(16f, 34f, 18f, 48f, fold); cv.drawLine(32f, 33f, 30f, 47f, fold)
        cv.drawLine(14f, 52f, 34f, 52f, solid(shade(l.top, 0.4f)).apply { strokeWidth = 1.4f; alpha = 140 })   // belt line

        // ---- arms
        val sleeve = lit(l.top, 8f, 40f)
        if (aim) {
            // both arms forward, hands together, gun pointing straight at the player
            limb(cv, 14f, 27f, 19f, 38f, 6f, 5f, sleeve)
            limb(cv, 34f, 27f, 29f, 38f, 6f, 5f, sleeve)
            limb(cv, 19f, 38f, 23f, 35f, 4.8f, 4f, sleeve)
            limb(cv, 29f, 38f, 25f, 35f, 4.8f, 4f, sleeve)
            cv.drawOval(RectF(20.5f, 33f, 27.5f, 39f), skinP)                      // clasped hands
            cv.drawRoundRect(RectF(21f, 29.5f, 27f, 35.5f), 1f, 1f, lit(0xFF2A2A2C.toInt(), 21f, 27f, 1.6f, 0.6f))   // gun, end-on
            cv.drawCircle(MUZZLE_X, MUZZLE_Y - 0.5f, 1.3f, solid(0xFF050505.toInt()))
        } else {
            val swing = step * 0.8f
            limb(cv, 13f, 27f, 11f + swing, 50f, 6f, 5f, sleeve)
            limb(cv, 35f, 27f, 37f - swing, 50f, 6f, 5f, sleeve)
            cv.drawCircle(11f + swing, 51.5f, 2.6f, skinP)
            cv.drawCircle(37f - swing, 51.5f, 2.6f, skinP)
            // pistol held down at the side
            cv.drawRoundRect(RectF(36f - swing, 51f, 39f - swing, 59f), 0.8f, 0.8f, lit(0xFF2A2A2C.toInt(), 36f, 39f, 1.6f, 0.6f))
        }

        // ---- neck + head
        cv.drawRect(21f, 18f, 27f, 24f, lit(l.skin, 21f, 27f, 1.0f, 0.55f))
        val head = RectF(18f, 5f, 30f, 20.5f)
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(21.5f, 10f, 11f, intArrayOf(shade(l.skin, 1.3f), l.skin, shade(l.skin, 0.5f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawOval(RectF(16.8f, 10.5f, 19.4f, 15f), face); cv.drawOval(RectF(28.6f, 10.5f, 31.2f, 15f), face)   // ears
        cv.drawOval(head, face)
        if (l.mask != null) {
            cv.drawOval(head, lit(l.mask, 18f, 30f, 1.6f, 0.6f))
            cv.drawOval(RectF(19.6f, 10.6f, 23.4f, 13.6f), face); cv.drawOval(RectF(24.6f, 10.6f, 28.4f, 13.6f), face)
            cv.drawCircle(21.6f, 12.1f, 0.8f, solid(0xFF101010.toInt())); cv.drawCircle(26.4f, 12.1f, 0.8f, solid(0xFF101010.toInt()))
            cv.drawOval(RectF(22.2f, 15.6f, 25.8f, 17.8f), solid(shade(l.skin, 0.7f)))
        } else {
            // hair cap
            cv.drawPath(Path().apply { moveTo(18f, 11f); quadTo(18f, 3.6f, 24f, 3.8f); quadTo(30f, 3.6f, 30f, 11f); quadTo(24f, 7.5f, 18f, 11f); close() },
                lit(l.hair, 18f, 30f, 1.5f, 0.6f))
            val dark = solid(shade(l.skin, 0.35f))
            if (l.shades) {
                cv.drawRoundRect(RectF(19f, 10.6f, 29f, 13.6f), 1.2f, 1.2f, solid(0xFF0C0C0E.toInt()))
                cv.drawLine(20f, 11.2f, 22f, 11.2f, solid(0x99FFFFFF.toInt()).apply { strokeWidth = 0.6f })   // glint
            } else {
                cv.drawLine(19.8f, 10.2f, 22.8f, 10f, dark.apply { strokeWidth = 0.9f })    // brows
                cv.drawLine(25.2f, 10f, 28.2f, 10.2f, dark)
                cv.drawCircle(21.5f, 12.2f, 0.75f, solid(0xFF1A1410.toInt())); cv.drawCircle(26.5f, 12.2f, 0.75f, solid(0xFF1A1410.toInt()))
            }
            cv.drawLine(24.6f, 12.5f, 25.2f, 15.4f, solid(shade(l.skin, 0.55f)).apply { strokeWidth = 0.8f })   // nose shadow
            cv.drawLine(22.4f, 17.3f, 25.8f, 17.1f, solid(shade(l.skin, 0.4f)).apply { strokeWidth = 0.8f })   // mouth
            // jaw shadow / stubble on the dark side
            cv.drawOval(RectF(24f, 13f, 30f, 20.5f), solid(shade(l.skin, 0.55f)).apply { alpha = 90 })
        }
        l.hat?.let { hat ->
            if (l.longCoat) {   // fedora: brim + crown with a band
                cv.drawOval(RectF(14f, 6.5f, 34f, 10f), lit(hat, 14f, 34f))
                cv.drawRoundRect(RectF(18.5f, 1.5f, 29.5f, 8.5f), 2.5f, 2.5f, lit(hat, 18f, 30f))
                cv.drawRect(18.5f, 6.4f, 29.5f, 7.6f, solid(shade(hat, 0.35f)))
            } else {            // bandana
                cv.drawPath(Path().apply { moveTo(17.6f, 9.5f); quadTo(18f, 3.4f, 24f, 3.6f); quadTo(30f, 3.4f, 30.4f, 9.5f); close() }, lit(hat, 18f, 30f))
            }
        }

        digitize(bmp, l.seed * 31 + frame)
        return bmp
    }

    /** Video-capture look: slight warm cast, 5-bit colour, per-pixel grain. */
    private fun digitize(bmp: Bitmap, seed: Int) {
        val r = Random(seed)
        val px = IntArray(W * H)
        bmp.getPixels(px, 0, W, 0, 0, W, H)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a < 100) { px[i] = 0; continue }            // hard matte edge, like a chroma-keyed actor
            val n = r.nextInt(-14, 15)
            fun ch(v: Int, warm: Int) = (((v + n + warm).coerceIn(0, 255)) and 0xF8)
            px[i] = (0xFF shl 24) or (ch(c shr 16 and 255, 6) shl 16) or (ch(c shr 8 and 255, 0) shl 8) or ch(c and 255, -6)
        }
        bmp.setPixels(px, 0, W, 0, 0, W, H)
    }
}
