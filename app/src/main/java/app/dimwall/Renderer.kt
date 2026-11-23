package app.dimwall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The visible part of the source image, as fractions (0..1) of its width and height. */
data class Crop(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Slider values. All of them are "how much of the effect":
 * blur 0 = sharp, 1 = maximum; the other three are 1 = untouched, lower = more muted.
 */
data class Effects(
    val blur: Float = 0f,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 1f,
) {
    val isNeutral: Boolean
        get() = blur <= 0f && saturation >= 1f && contrast >= 1f && brightness >= 1f

    companion object {
        const val MIN_CONTRAST = 0.3f
        const val MIN_BRIGHTNESS = 0.3f

        /** Gaussian sigma at maximum blur, as a fraction of the image width. */
        const val MAX_BLUR_SIGMA_FRACTION = 0.04f
    }
}

/**
 * The single pipeline used for both the live preview and the final wallpaper:
 * crop + scale -> blur -> colour adjustments.
 * Every size-dependent value is relative to the image width, so a small preview and the
 * full-resolution result look the same.
 */
object Renderer {

    /** Cuts [crop] out of [src] and scales it to exactly [outW] x [outH]. */
    fun cropScale(src: Bitmap, crop: Crop, outW: Int, outH: Int): Bitmap {
        val left = floor(crop.left * src.width).toInt().coerceIn(0, src.width - 1)
        val top = floor(crop.top * src.height).toInt().coerceIn(0, src.height - 1)
        val right = ceil((crop.left + crop.width) * src.width).toInt().coerceIn(left + 1, src.width)
        val bottom = ceil((crop.top + crop.height) * src.height).toInt().coerceIn(top + 1, src.height)

        val region = Bitmap.createBitmap(src, left, top, right - left, bottom - top)
        val reduced = shrinkByHalves(region, outW, outH)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(reduced, null, Rect(0, 0, outW, outH), paint)
        return out
    }

    /** Scales [bmp] down so that its longer edge is at most [maxEdge]. Returns [bmp] if already small. */
    fun downscaleToLongEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / longEdge
        val targetW = max(1, (bmp.width * scale).roundToInt())
        val targetH = max(1, (bmp.height * scale).roundToInt())
        val reduced = shrinkByHalves(bmp, targetW, targetH)
        return if (reduced.width == targetW && reduced.height == targetH) {
            reduced
        } else {
            Bitmap.createScaledBitmap(reduced, targetW, targetH, true)
        }
    }

    /** Applies blur, then saturation/contrast/brightness. [check] is called between heavy steps (for cancellation). */
    fun applyEffects(base: Bitmap, fx: Effects, check: () -> Unit): Bitmap {
        if (fx.isNeutral) return base
        var result = base
        if (fx.blur > 0f) {
            val sigma = fx.blur * Effects.MAX_BLUR_SIGMA_FRACTION * base.width
            result = gaussianBlur(result, sigma, check)
        }
        check()
        if (fx.saturation < 1f || fx.contrast < 1f || fx.brightness < 1f) {
            result = adjustColors(result, fx)
        }
        return result
    }

    // ---- scaling helpers -------------------------------------------------------------------

    /** Halves the bitmap while it is still at least twice as large as the target (better quality than one big step). */
    private fun shrinkByHalves(bmp: Bitmap, targetW: Int, targetH: Int): Bitmap {
        var current = bmp
        while (current.width / 2 >= targetW && current.height / 2 >= targetH) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        return current
    }

    // ---- colour ----------------------------------------------------------------------------

    private fun adjustColors(src: Bitmap, fx: Effects): Bitmap {
        val matrix = ColorMatrix()
        matrix.setSaturation(fx.saturation)

        // Contrast around mid-grey: out = c * in + 128 * (1 - c)
        val c = fx.contrast
        val t = 128f * (1f - c)
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        // Brightness (dimming): out = b * in
        val b = fx.brightness
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    b, 0f, 0f, 0f, 0f,
                    0f, b, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // ---- blur ------------------------------------------------------------------------------

    /**
     * Gaussian blur approximated by three successive box blurs (horizontal + vertical each).
     * Works on any Android version, no RenderEffect / RenderScript. Edges are clamped.
     */
    private fun gaussianBlur(src: Bitmap, sigma: Float, check: () -> Unit): Bitmap {
        val w = src.width
        val h = src.height
        val maxRadius = (min(w, h) - 1) / 2
        val radii = boxRadii(sigma.toDouble(), 3).map { it.coerceAtMost(maxRadius) }
        if (radii.all { it <= 0 }) return src

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)

        for (r in radii) {
            if (r <= 0) continue
            check()
            boxBlurHorizontal(pixels, scratch, w, h, r)
            boxBlurVertical(scratch, pixels, w, h, r)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Box radii whose repeated application approximates a Gaussian of the given sigma. */
    private fun boxRadii(sigma: Double, passes: Int): IntArray {
        val idealWidth = sqrt(12.0 * sigma * sigma / passes + 1.0)
        var lower = floor(idealWidth).toInt()
        if (lower % 2 == 0) lower--
        val upper = lower + 2
        val m = Math.round(
            (12.0 * sigma * sigma - passes * lower * lower - 4.0 * passes * lower - 3.0 * passes) /
                (-4.0 * lower - 4.0),
        ).toInt()
        return IntArray(passes) { i -> ((if (i < m) lower else upper) - 1) / 2 }
    }

    private fun pack(r: Int, g: Int, b: Int, div: Int): Int {
        val half = div / 2
        return (0xFF shl 24) or
            (((r + half) / div) shl 16) or
            (((g + half) / div) shl 8) or
            ((b + half) / div)
    }

    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = pack(sr, sg, sb, div)
                val add = src[row + min(x + r + 1, w - 1)]
                val rem = src[row + max(x - r, 0)]
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun boxBlurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val sumR = IntArray(w)
        val sumG = IntArray(w)
        val sumB = IntArray(w)
        for (i in -r..r) {
            val row = i.coerceIn(0, h - 1) * w
            for (x in 0 until w) {
                val p = src[row + x]
                sumR[x] += (p shr 16) and 0xFF
                sumG[x] += (p shr 8) and 0xFF
                sumB[x] += p and 0xFF
            }
        }
        for (y in 0 until h) {
            val out = y * w
            for (x in 0 until w) {
                dst[out + x] = pack(sumR[x], sumG[x], sumB[x], div)
            }
            val addRow = min(y + r + 1, h - 1) * w
            val remRow = max(y - r, 0) * w
            for (x in 0 until w) {
                val add = src[addRow + x]
                val rem = src[remRow + x]
                sumR[x] += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sumG[x] += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sumB[x] += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }
}
