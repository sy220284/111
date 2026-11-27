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

    private fun escapedByte(value: String, index: Int): Int? {
        if (index + 2 >= value.length || value[index] != '%') return null
        val pair = value.substring(index + 1, index + 3)
        if (pair.any { it !in "0123456789abcdefABCDEF" }) return null
        return pair.toInt(16)
    }

    private fun decodePath(value: String): String {
        // Decode once, including UTF-8 escapes. '+' is an ordinary filename character.
        // A literal '%' that is not part of an escape must also survive.
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            if (escapedByte(value, index) == null) {
                result.append(value[index++])
                continue
            }
            val bytes = ByteArrayOutputStream()
            while (index < value.length) {
                val next = escapedByte(value, index) ?: break
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

    // Markdown link destinations use URI escaping; declarative file cards contain native paths.
    fun fileCard(value: String): String? = file(value, decodeBare = false)
    fun file(value: String): String? = file(value, decodeBare = true)
    private fun file(value: String, decodeBare: Boolean): String? {
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
                val bare = value.replace('\\', '/')
                if (decodeBare) decodePath(bare) else bare
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
