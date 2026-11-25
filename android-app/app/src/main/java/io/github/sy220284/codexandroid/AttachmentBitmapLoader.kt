package io.github.sy220284.codexandroid

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AttachmentBitmapInfo(val bitmap: ImageBitmap, val width: Int, val height: Int, val mime: String?)

/** Shared bounded decoder; callers publish results on Main after the IO work. */
internal suspend fun decodeAttachmentBitmap(path: String, maxSide: Int): ImageBitmap? = decodeAttachmentInfo(path, maxSide)?.bitmap

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
