package dev.toruu.airgrab.data.payload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reliable, dependency-free thumbnail decoder for local Content/SAF/MediaStore URIs.
 * Handles 50MP+ photos, HEIC, EXIF orientation, and video frames without GPU texture OOMs.
 */
object MediaPreviewLoader {
    private const val TAG = "AirGrab_Preview"

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    private val videoExtensions = setOf("mp4", "mov", "mkv", "webm", "3gp", "avi", "m4v")

    fun inferMediaMime(displayName: String?, declaredMime: String?): String? {
        val mime = declaredMime?.lowercase()?.takeIf { it != "*/*" && it != "application/octet-stream" }
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            return mime
        }
        val ext = displayName?.substringAfterLast('.', "")?.lowercase() ?: return null
        return when {
            ext in imageExtensions -> "image/$ext"
            ext in videoExtensions -> "video/$ext"
            else -> null
        }
    }

    suspend fun loadThumbnail(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
        maxDimensionPx: Int = 960
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri)
        val isVideo = resolvedMime?.startsWith("video/", ignoreCase = true) == true

        // 1. Try system MediaStore thumbnail cache when available (fast for gallery/saved URIs).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    uri,
                    Size(maxDimensionPx, maxDimensionPx),
                    null
                )
            }.getOrNull()?.let { bmp ->
                return@withContext bmp.asImageBitmap()
            }
        }

        // 2. Decode video frame via MediaMetadataRetriever.
        if (isVideo) {
            decodeVideoFrame(context, uri, maxDimensionPx)?.let { bmp ->
                return@withContext bmp.asImageBitmap()
            }
        }

        // 3. Decode bounded image bitmap (with automatic EXIF orientation on API 28+).
        decodeBoundedImage(context, uri, maxDimensionPx)?.asImageBitmap()
    }

    private fun decodeVideoFrame(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxDim,
                    maxDim
                )
            } else {
                retriever.frameAtTime?.let { scaleBitmapIfNeeded(it, maxDim) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video thumbnail decode failed for $uri: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeBoundedImage(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxDim && longest > 0) {
                        val scale = maxDim.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (w * scale).roundToInt().coerceAtLeast(1),
                            (h * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                    // Software bitmap avoids cross-layer GPU upload artifacts in Compose previews.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                Log.w(TAG, "ImageDecoder failed for $uri, trying BitmapFactory: ${e.message}")
            }
        }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }?.let { scaleBitmapIfNeeded(it, maxDim) }
        } catch (e: Exception) {
            Log.w(TAG, "BitmapFactory decode failed for $uri: ${e.message}")
            null
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim || longest <= 0) return bitmap
        val scale = maxDim.toFloat() / longest.toFloat()
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}

sealed interface PreviewBitmapState {
    object Loading : PreviewBitmapState
    data class Ready(val bitmap: ImageBitmap) : PreviewBitmapState
    object Unavailable : PreviewBitmapState
}

@Composable
fun rememberPreviewBitmap(uri: Uri?, mimeType: String? = null): PreviewBitmapState {
    val context = LocalContext.current
    return produceState<PreviewBitmapState>(
        initialValue = if (uri != null) PreviewBitmapState.Loading else PreviewBitmapState.Unavailable,
        key1 = uri,
        key2 = mimeType
    ) {
        if (uri == null) {
            value = PreviewBitmapState.Unavailable
            return@produceState
        }
        value = PreviewBitmapState.Loading
        val decoded = MediaPreviewLoader.loadThumbnail(context, uri, mimeType)
        value = if (decoded != null) PreviewBitmapState.Ready(decoded) else PreviewBitmapState.Unavailable
    }.value
}
