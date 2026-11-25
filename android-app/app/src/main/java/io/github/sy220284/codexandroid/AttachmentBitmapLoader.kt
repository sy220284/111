package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AttachmentBitmapInfo(val bitmap: ImageBitmap, val width: Int, val height: Int, val mime: String?)

/** Shared bounded decoder; callers publish results on Main after the IO work. */
internal suspend fun decodeAttachmentInfo(path: String, maxSide: Int): AttachmentBitmapInfo? =
    withContext(Dispatchers.IO) {
        require(maxSide > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
        })?.asImageBitmap()?.let { AttachmentBitmapInfo(it, bounds.outWidth, bounds.outHeight, bounds.outMimeType) }
    }

/** One lifecycle and publication policy for thumbnail and full-size previews.
 * A new path/retry gets a fresh state; the canceled request cannot publish to it.
 */
internal data class AttachmentImageState(val loaded: Boolean, val info: AttachmentBitmapInfo?) {
    val bitmap: ImageBitmap? get() = info?.bitmap
}

@Composable
internal fun rememberAttachmentImage(path: String, maxSide: Int, retry: Int = 0): State<AttachmentImageState> =
    key(path, maxSide, retry) {
        loadCodexUiState(AttachmentImageState(false, null), path, maxSide, retry) {
            AttachmentImageState(true, decodeAttachmentInfo(path, maxSide))
        }
    }
