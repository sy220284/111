package io.github.sy220284.codexandroid

import java.util.Locale

/** Select an allowed local decoder from provider metadata. Actual bytes are
 * validated by LocalImageAttachments before the managed path is returned. */
internal object MediaImportFormat {
    private val mimeExtensions = mapOf(
        "image/jpeg" to "jpg", "image/jpg" to "jpg",
        "image/png" to "png", "image/webp" to "webp",
        "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
        "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
        "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a",
        "audio/webm" to "webm", "audio/ogg" to "ogg", "application/ogg" to "ogg",
        "video/mp4" to "mp4", "video/x-mp4" to "mp4", "video/mpeg4" to "mp4"
    )
    private val supportedExtensions = mimeExtensions.values.toSet() + "jpeg"
    private fun mime(type: String?) = type.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    fun requiresName(type: String?): Boolean = mime(type) in setOf(
        "", "*/*", "application/octet-stream", "binary/octet-stream", "application/mp4",
        "application/x-mp4", "image/*", "audio/*", "video/*"
    )

    fun extension(type: String?, displayName: String?): String {
        val normalized = mime(type)
        mimeExtensions[normalized]?.let { return it }
        if (requiresName(type)) {
            val suffix = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
            val ext = if (suffix == "jpeg") "jpg" else suffix
            val allowed = when (normalized) {
                "application/mp4", "application/x-mp4" -> setOf("mp4", "m4a")
                "image/*" -> setOf("jpg", "png", "webp")
                "audio/*" -> setOf("wav", "mp3", "m4a", "ogg", "webm")
                "video/*" -> setOf("mp4")
                else -> supportedExtensions
            }
            if (ext in allowed) return ext
        }
        throw IllegalArgumentException("请选择 JPEG、PNG、WebP、WAV、MP3、M4A、OGG、WebM 音频或 MP4 视频")
    }
}
