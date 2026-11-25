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

    @Test fun blocksTraversalAndCredentials() {
        for (path in listOf(
            "/tmp/passwd", "files/../shared_prefs/settings.xml",
            "files/projects/./bad.txt", "files/projects\\..\\auth.json",
            "files/codex-home/auth.json", "shared_prefs/auth.xml",
            "files/.codex-restore-staged/private.txt", "manifest.txt"
        )) assertFalse(path, LocalDataArchive.validatePath(path))
    }
}
