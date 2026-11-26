package io.github.sy220284.codexandroid

import java.net.URI
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** File paths and URI links are distinct; never pass file URIs directly to Android. */
internal object ReplyLinkPolicy {
    fun web(value: String) = value.startsWith("https://", true) || value.startsWith("http://", true)

    private fun decodePath(value: String): String {
        // Decode once, including UTF-8 escapes. '+' is an ordinary filename character.
        // A literal '%' that is not part of an escape must also survive.
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (value[index] != '%' || index + 2 >= value.length || value.substring(index + 1, index + 3).toIntOrNull(16) == null) {
                result.append(value[index++])
                continue
            }
            val bytes = ByteArrayOutputStream()
            while (index + 2 < value.length && value[index] == '%') {
                val next = value.substring(index + 1, index + 3).toIntOrNull(16) ?: break
                require(next in 0..255 && value[index + 1].isLetterOrDigit() && value[index + 2].isLetterOrDigit())
                bytes.write(next)
                index += 3
            }
            // Reject malformed UTF-8 instead of substituting a different filename.
            result.append(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())))
        }
        return result.toString()
    }

    fun file(value: String): String? {
        if (value.isBlank() || value.length > 4096 || value.any(Char::isISOControl) || value.startsWith("//")) return null
        val path = try {
            val windows = Regex("^[A-Za-z]:[/\\\\].*").matches(value)
            val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(value)?.groupValues?.get(1)
            if (scheme != null && !windows) {
                if (scheme.lowercase(java.util.Locale.ROOT) !in setOf("file", "sandbox")) return null
                val uri = URI(value.replace(" ", "%20"))
                // File URIs with query/fragment are ambiguous: reject rather than opening a different file.
                if (uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null) return null
                val decoded = uri.rawPath?.let(::decodePath)?.takeIf { it.isNotBlank() } ?: return null
                if (Regex("^/[A-Za-z]:/.*").matches(decoded)) decoded.removePrefix("/") else decoded
            } else {
                // Bare paths can legally contain '#' and '?' on POSIX; never treat them as URI delimiters.
                decodePath(value.replace('\\', '/'))
            }
        } catch (_: IllegalArgumentException) { return null }
          catch (_: java.net.URISyntaxException) { return null }
          catch (_: CharacterCodingException) { return null }
        val normalized = path.replace('\\', '/').removePrefix("./")
        if (normalized.isBlank() || normalized.startsWith("//") || normalized.any(Char::isISOControl) ||
            normalized.split('/').any { it == ".." || it == "." }) return null
        return normalized
    }
    fun destination(value: String): String? = if (web(value)) value else value.takeIf { file(it) != null }
}
