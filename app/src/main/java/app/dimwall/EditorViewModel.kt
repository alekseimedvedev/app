package app.dimwall

import android.app.Application
import android.app.WallpaperManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    enum class Target(val label: String) { HOME("Home"), LOCK("Lock"), BOTH("Both") }

    private val appContext: Application = application

    /** Wallpaper size = real screen size, read from the system. */
    val outWidth: Int
    val outHeight: Int

    init {
        val size = ImageLoader.screenSizePx(application)
        outWidth = size.first
        outHeight = size.second
    }

    val outputAspect: Float get() = outWidth.toFloat() / outHeight

    // ---- UI state --------------------------------------------------------------------------

    var hasImage by mutableStateOf(false)
        private set
    var effects by mutableStateOf(Effects())
        private set
    var crop by mutableStateOf(Crop(0f, 0f, 1f, 1f))
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var original by mutableStateOf<ImageBitmap?>(null)
        private set
    var showOriginal by mutableStateOf(false)
    var target by mutableStateOf(Target.HOME)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private var zoom by mutableFloatStateOf(1f)

    // ---- internal state --------------------------------------------------------------------

    private var fullSource: Bitmap? = null
    private var previewSource: Bitmap? = null
    private var sourceAspect = 1f
    private var previewJob: Job? = null

    private val previewWidth = 420
    private val previewHeight: Int get() = (previewWidth / outputAspect).toInt()

    // ---- loading ---------------------------------------------------------------------------

    fun loadImage(uri: Uri) {
        viewModelScope.launch {
            message = null
            try {
                val full = withContext(Dispatchers.IO) { ImageLoader.decode(appContext, uri, FULL_SOURCE_MAX_EDGE) }
                val small = withContext(Dispatchers.Default) { Renderer.downscaleToLongEdge(full, PREVIEW_SOURCE_MAX_EDGE) }
                fullSource = full
                previewSource = small
                sourceAspect = full.width.toFloat() / full.height
                zoom = 1f
                crop = centeredCrop()
                hasImage = true
                schedulePreview()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = "Couldn't open this image."
            }
        }
    }

    // ---- editing ---------------------------------------------------------------------------

    fun updateEffects(newEffects: Effects) {
        effects = newEffects
        schedulePreview()
    }

    /** Resets sliders and framing. */
    fun resetAll() {
        effects = Effects()
        zoom = 1f
        crop = centeredCrop()
        message = null
        schedulePreview()
    }

    /**
     * Pinch / drag on the preview. [u], [v] is the gesture centre as a fraction of the preview size;
     * [panX], [panY] is the finger movement as a fraction of the preview size.
     */
    fun transformCrop(u: Float, v: Float, panX: Float, panY: Float, zoomChange: Float) {
        if (!hasImage) return
        val old = crop
        val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val (baseW, baseH) = baseSize()
        val w = baseW / newZoom
        val h = baseH / newZoom
        // The point of the image under the gesture centre stays under it; the finger drags the image.
        val pointX = old.left + u * old.width
        val pointY = old.top + v * old.height
        val left = (pointX - u * w - panX * w).coerceIn(0f, 1f - w)
        val top = (pointY - v * h - panY * h).coerceIn(0f, 1f - h)
        zoom = newZoom
        crop = Crop(left, top, w, h)
        schedulePreview()
    }

    private fun baseSize(): Pair<Float, Float> {
        val out = outputAspect
        return if (sourceAspect > out) (out / sourceAspect) to 1f else 1f to (sourceAspect / out)
    }

    private fun centeredCrop(): Crop {
        val (w, h) = baseSize()
        return Crop((1f - w) / 2f, (1f - h) / 2f, w, h)
    }

    // ---- preview ---------------------------------------------------------------------------

    private fun schedulePreview() {
        val src = previewSource ?: return
        val currentCrop = crop
        val currentEffects = effects
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val pair = withContext(Dispatchers.Default) {
                val base = Renderer.cropScale(src, currentCrop, previewWidth, previewHeight)
                ensureActive()
                val result = Renderer.applyEffects(base, currentEffects) { ensureActive() }
                base to result
            }
            original = pair.first.asImageBitmap()
            preview = pair.second.asImageBitmap()
        }
    }

    // ---- output ----------------------------------------------------------------------------

    fun setWallpaper() {
        val flags = when (target) {
            Target.HOME -> WallpaperManager.FLAG_SYSTEM
            Target.LOCK -> WallpaperManager.FLAG_LOCK
            Target.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }
        val label = target.label
        launchWork("Wallpaper set: $label") {
            val bitmap = renderFull()
            withContext(Dispatchers.IO) {
                WallpaperManager.getInstance(appContext).setBitmap(bitmap, null, true, flags)
            }
        }
    }

    fun saveToGallery() {
        launchWork("Saved to Pictures/DimWall") {
            val bitmap = renderFull()
            withContext(Dispatchers.IO) { saveJpeg(bitmap) }
        }
    }

    private suspend fun renderFull(): Bitmap {
        val src = fullSource ?: error("No image")
        val currentCrop = crop
        val currentEffects = effects
        return withContext(Dispatchers.Default) {
            val base = Renderer.cropScale(src, currentCrop, outWidth, outHeight)
            Renderer.applyEffects(base, currentEffects) { ensureActive() }
        }
    }

    private fun saveJpeg(bitmap: Bitmap) {
        val resolver = appContext.contentResolver
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "DimWall_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DimWall")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create the file")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("Couldn't open the file")
            stream.use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "JPEG encoding failed" }
            }
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    private fun launchWork(successMessage: String, block: suspend () -> Unit) {
        if (busy || !hasImage) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                block()
                message = successMessage
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                message = "Failed: ${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val FULL_SOURCE_MAX_EDGE = 4096
        const val PREVIEW_SOURCE_MAX_EDGE = 1600
        const val MAX_ZOOM = 8f
        const val JPEG_QUALITY = 95
    }
}
