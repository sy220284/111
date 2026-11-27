package io.github.sy220284.codexandroid

/** Only portable user content crosses the archive trust boundary. Unknown paths are denied by default. */
internal object BackupDataPolicy {
    private val preferences = setOf(
        "codex-unsent-drafts.xml", "visual-preferences.xml", "codex-diff-expansion.xml",
        "codex-recent-thread-queries.xml", "session-list-preferences.xml",
        "answer-versions.xml"
    )
    private val historyRoots = setOf("sessions", "archived_sessions")
    private val privateDirectories = setOf(PortableMediaFormat.ATTACHMENTS, PortableMediaFormat.ANALYSES,
        "editor-drafts", "answer-checkpoints", "terminal-transcript")
    private val hashName = Regex("[0-9a-f]{64}")

    fun safeSyntax(name: String): Boolean {
        if (name.contains('\\') || name.contains('\u0000') || name.startsWith('/') || name.endsWith('/')) return false
        val parts = name.split('/')
        return parts.size >= 2 && parts[0] in setOf("files", "shared_prefs") &&
            parts.none { it.isBlank() || it == "." || it == ".." }
    }

    fun allows(name: String): Boolean {
        if (!safeSyntax(name)) return false
        val parts = name.split('/')
        if (parts[0] == "shared_prefs") return parts.size == 2 && parts[1] in preferences
        return when (parts[1]) {
            "projects" -> parts.size >= 3
            "codex-home" -> {
                if (parts.size == 3) parts[2] in setOf("history.jsonl", "session_index.jsonl")
                else parts.size >= 4 && parts[2] in historyRoots && parts.last().endsWith(".jsonl")
            }
            PortableMediaFormat.ATTACHMENTS -> (parts.size == 3 && PortableMediaFormat.attachment(parts[2])) ||
                (parts.size == 4 && parts[2] == "history" && PortableMediaFormat.history(parts[3]))
            PortableMediaFormat.ANALYSES -> parts.size == 3 && PortableMediaFormat.analysis(parts[2])
            "editor-drafts" -> parts.size == 3 && parts[2].endsWith(".json") && hashName.matches(parts[2].removeSuffix(".json"))
            AnswerCheckpointFormat.ROOT -> parts.size == 4 && when (parts[2]) {
                AnswerCheckpointFormat.OBJECTS -> AnswerCheckpointFormat.isObjectName(parts[3])
                AnswerCheckpointFormat.MANIFESTS -> AnswerCheckpointFormat.isManifestName(parts[3])
                else -> false
            }
            "terminal-transcript" -> parts.size == 3 && parts[2] in setOf("stdout.bin", "stderr.bin")
            else -> false
        }
    }

    /** Avoid traversing runtime, credentials and unknown private directories during export. */
    fun mayContain(name: String): Boolean {
        if (!safeSyntax(name)) return false
        val parts = name.split('/')
        if (parts[0] != "files") return false
        return when (parts[1]) {
            "projects" -> true
            "codex-home" -> parts.size == 2 || parts[2] in historyRoots
            in privateDirectories -> parts.size == 2 ||
                (parts[1] == PortableMediaFormat.ATTACHMENTS && parts.size == 3 && parts[2] == "history") ||
                (parts[1] == "answer-checkpoints" && parts.size == 3 && parts[2] in setOf("objects", "manifests"))
            else -> false
        }
    }
}
