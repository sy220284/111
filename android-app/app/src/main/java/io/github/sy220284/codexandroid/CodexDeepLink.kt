package io.github.sy220284.codexandroid

import java.net.URI

/** External links may select a thread but cannot pass instructions or filesystem paths. */
internal object CodexDeepLink {
    private val validId = Regex("""[A-Za-z0-9_-]{8,128}""")

    fun threadId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = try { URI(raw) } catch (failure: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme != "codex" || uri.host != "thread" ||
            uri.query != null || uri.fragment != null || uri.userInfo != null ||
            uri.port != -1) return null
        val path = uri.path ?: return null
        if (!path.startsWith('/') || path.count { it == '/' } != 1) return null
        val id = path.substring(1)
        return id.takeIf { validId.matches(it) }
    }
}
