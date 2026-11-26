package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class LocalDataArchivePathTest {
    @Test fun approvesPrivateDataPaths() {
        for (path in listOf(
            "files/projects/demo/src/Main.kt", "files/codex-home/sessions/2026/session.jsonl",
            "shared_prefs/codex-unsent-drafts.xml"
        )) assertTrue(path, LocalDataArchive.validatePath(path))
    }

    @Test fun runtimeAndInstalledDependenciesAreNotRestorable() {
        for (path in listOf("files/native-tools/git", "files/developer-runtime/v/lib/python.py",
            "files/developer-packages/node/lib/node_modules/tool.js", "files/developer-system-ca.pem",
            "files/developer-system-ca.pem.bak")) assertFalse(path, LocalDataArchive.validatePath(path))
        assertTrue(LocalDataArchive.validatePath("files/projects/demo/developer-runtime/main.py"))
    }

    @Test fun onlyExplicitPortableDataCanCrossTrustBoundary() {
        for (path in listOf(
            "files/codex-home/config.toml", "files/codex-home/config.toml.bak",
            "files/codex-home/rules/default.rules", "files/codex-home/skills/demo/SKILL.md",
            "files/codex-home/log/codex.log", "files/codex-home/sessions/2026/config.toml",
            "files/codex-home/state_5.sqlite", "files/approval-audit.json",
            "files/unknown-future-runtime/payload", "shared_prefs/codex-remote.xml",
            "shared_prefs/visual-preferences.xml/extra", "shared_prefs/new-runtime.xml"
        )) assertFalse(path, LocalDataArchive.validatePath(path))
        for (path in listOf(
            "files/codex-home/archived_sessions/rollout.jsonl",
            "files/codex-home/session_index.jsonl", "files/codex-home/history.jsonl",
            "shared_prefs/visual-preferences.xml", "files/projects/demo/config.toml",
            "files/codex-turn-images/12345678-1234-1234-1234-123456789abc.png"
        )) assertTrue(path, LocalDataArchive.validatePath(path))
        assertFalse(BackupDataPolicy.mayContain("files/codex-home/skills"))
        assertTrue(BackupDataPolicy.mayContain("files/codex-home/sessions/2026"))
    }

    @Test fun blocksTraversalAndCredentials() {
        for (path in listOf(
            "/tmp/passwd", "files/../shared_prefs/settings.xml",
            "files/projects/./bad.txt", "files/projects\\..\\auth.json",
            "files/codex-home/auth.json", "shared_prefs/auth.xml",
            "files/.codex-restore-staged/private.txt", "manifest.txt"
        )) assertFalse(path, LocalDataArchive.validatePath(path))
    }
}
