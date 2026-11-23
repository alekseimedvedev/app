package app.dimwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

object ImageLoader {

    /**
     * Decodes [uri] into a software bitmap whose longer edge is at most about [maxEdge].
     * ImageDecoder applies the EXIF rotation by itself.
     */
    fun decode(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longEdge = max(info.size.width, info.size.height)
            var sample = 1
            while (longEdge / sample > maxEdge) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }

    /** Real screen size in pixels as (width, height) in portrait. */
    @Suppress("DEPRECATION")
    fun screenSizePx(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return min(bounds.width(), bounds.height()) to max(bounds.width(), bounds.height())
    }
}
