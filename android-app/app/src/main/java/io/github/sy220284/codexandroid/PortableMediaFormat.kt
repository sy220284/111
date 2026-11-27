package io.github.sy220284.codexandroid

/** One durable filename contract for import, drafts, history and portable archives. */
internal object PortableMediaFormat {
    const val ATTACHMENTS = "codex-turn-images"
    const val ANALYSES = "media-analysis-results"
    private const val UUID = "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    private val original = Regex("$UUID\\.(jpg|png|webp|wav|mp3|m4a|ogg|webm|mp4|av\\.mp4|silent\\.mp4)")
    private val sidecar = Regex("($UUID\\.(?:mp4|av\\.mp4|silent\\.mp4))\\.(?:metadata\\.json|(?:visual\\.|audio\\.)?timeline\\.json|segment-[0-9]\\.(?:jpg|m4a))")
    private val message = Regex("message-$UUID\\.json")
    private val analysis = Regex("[0-9a-f]{64}(?:-[0-9a-f]{64})?\\.json")
    fun original(name: String) = original.matches(name)
    fun sidecarSource(name: String): String? = sidecar.matchEntire(name)?.groupValues?.get(1)
    fun attachment(name: String) = original(name) || sidecarSource(name) != null
    fun historyMessage(name: String) = message.matches(name)
    fun history(name: String) = attachment(name) || historyMessage(name)
    fun analysis(name: String) = analysis.matches(name)
}
