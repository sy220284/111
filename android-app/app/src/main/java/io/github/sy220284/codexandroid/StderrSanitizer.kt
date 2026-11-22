package io.github.sy220284.codexandroid

/** Bounded stderr diagnostics with credential-like fragments removed in memory. */
internal object StderrSanitizer {
    private val bearer = Regex("""(?i)\bBearer\s+[-A-Za-z0-9._~+/=]{8,}""")
    private val apiKeys = Regex("""\b(?:sk|ghp|github_pat)-?[-A-Za-z0-9_]{12,}\b""")
    private val assignment = Regex(
        """(?i)\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)\s*[:=]\s*["']?[-A-Za-z0-9._~+/=]{6,}""")
    private val query = Regex(
        """(?i)([?&](?:token|key|secret|password|access_token)=)[^&\s]+""")

    fun redact(raw: String): String =
        raw.replace(bearer, "Bearer [已隐藏]")
            .replace(apiKeys, "[已隐藏密钥]")
            .replace(assignment, "[已隐藏凭据字段]")
            .replace(query, "$1[已隐藏]")
}
