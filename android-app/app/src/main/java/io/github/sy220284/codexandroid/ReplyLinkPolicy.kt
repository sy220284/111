package io.github.sy220284.codexandroid

import java.net.URI

/** File links and file cards share the workspace-bound opener; never pass file URIs to Android. */
internal object ReplyLinkPolicy {
    fun web(value: String) = value.startsWith("https://", true) || value.startsWith("http://", true)
    fun file(value: String): String? {
        if (value.isBlank() || value.length > 4096 || value.any(Char::isISOControl) || value.startsWith("//")) return null
        val path = try {
            // A Windows path is a path, not a custom URI scheme.
            val input = value.replace('\\', '/')
            val windows = Regex("^[A-Za-z]:/.*").matches(input)
            val uri = URI((if (windows) "file:///" else "") + input.replace(" ", "%20"))
            if (uri.rawAuthority != null || uri.rawQuery != null ||
                (uri.scheme != null && uri.scheme.lowercase(java.util.Locale.ROOT) !in setOf("file", "sandbox"))) return null
            val decoded = uri.path?.takeIf { it.isNotBlank() } ?: return null
            if (windows || (uri.scheme.equals("file", true) && Regex("^/[A-Za-z]:/.*").matches(decoded)))
                decoded.removePrefix("/") else decoded
        } catch (_: java.net.URISyntaxException) { return null }
        val normalized = path.replace('\\', '/').removePrefix("./")
        if (normalized.isBlank() || normalized.startsWith("//") || normalized.any(Char::isISOControl) ||
            normalized.split('/').any { it == ".." || it == "." }) return null
        return normalized
    }
    fun destination(value: String): String? = if (web(value)) value else value.takeIf { file(it) != null }
}
