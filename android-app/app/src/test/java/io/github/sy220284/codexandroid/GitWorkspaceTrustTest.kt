package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GitWorkspaceTrustTest {
    @Test fun sharedWorkspaceTrustAllowsItsRepositoriesAndRejectsOutsideOwnership() {
        val temporary = Files.createTempDirectory("git-workspace-trust").toFile()
        try {
            val workspace = File(temporary, "workspace").apply { mkdir() }
            val inside = File(workspace, "project").apply { mkdir() }
            val outside = File(temporary, "outside").apply { mkdir() }
            fun git(directory: File, env: Map<String, String>, vararg arguments: String): Int {
                val process = ProcessBuilder(listOf("git") + arguments).directory(directory)
                    .redirectErrorStream(true).apply { environment().putAll(env) }.start()
                process.inputStream.bufferedReader().use { it.readText() }
                return process.waitFor()
            }
            assertEquals(0, git(inside, emptyMap(), "init", "-q"))
            assertEquals(0, git(outside, emptyMap(), "init", "-q"))
            val env = mutableMapOf("GIT_TEST_ASSUME_DIFFERENT_OWNER" to "1", "GIT_CONFIG_NOSYSTEM" to "1",
                "GIT_CONFIG_GLOBAL" to File(temporary, "empty-config").path)
            assertEquals(128, git(inside, env, "status", "--porcelain"))
            GitWorkspaceTrust.configure(workspace, env)
            assertEquals(0, git(inside, env, "status", "--porcelain"))
            assertEquals(128, git(outside, env, "status", "--porcelain"))
        } finally { temporary.deleteRecursively() }
    }
}
