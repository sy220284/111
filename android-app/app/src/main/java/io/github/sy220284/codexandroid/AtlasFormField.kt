package io.github.sy220284.codexandroid

internal object ProviderAddressPolicy {
    fun defaultName(raw: String): String = if (valid(raw)) java.net.URI(raw.trim()).host.orEmpty() else ""
    fun validCredentialReference(raw: String): Boolean =
        raw.isBlank() || raw.trim().matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))

    fun valid(raw: String): Boolean {
        val uri = try { java.net.URI(raw.trim()) } catch (_: java.net.URISyntaxException) { return false }
        return !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.port != 0 && uri.port <= 65535 &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    }
}
