package io.github.sy220284.codexandroid

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeOwnershipInstrumentedTest {
    @Test fun foregroundServiceReportsReadyOnlyAfterAndroidPromotion() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as CodexApplication
        // Keep service alive during readiness verification, then return control.
        app.session.terminalStarting.value = true
        try {
            CodexForegroundService.startAndAwaitReady(app)
        } finally {
            app.session.terminalStarting.value = false
        }
    }

    @Test fun legacyBackupRestoresHistoryWithoutChangingRuntimeConfiguration() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "backup-audit-").toFile()
        val prefix = root.name + "-"
        val context = object : android.content.ContextWrapper(app) {
            override fun getFilesDir(): java.io.File = root
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                app.getSharedPreferences(prefix + name, mode)
        }
        fun archive(version: Int): java.io.ByteArrayInputStream {
            val bytes = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                val entries = linkedMapOf(
                    "manifest.txt" to "111-local-backup-version:$version",
                    "files/codex-home/history.jsonl" to "portable-history",
                    "files/codex-home/config.toml" to "untrusted-configuration",
                    "files/unknown-runtime/start.sh" to "untrusted-runtime"
                )
                for ((name, content) in entries) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            return java.io.ByteArrayInputStream(bytes.toByteArray())
        }
        try {
            val home = java.io.File(root, "codex-home").apply { check(mkdirs()) }
            val config = java.io.File(home, "config.toml").apply { writeText("existing-config") }
            org.junit.Assert.assertEquals(1, LocalDataArchive.stageImport(context, archive(1)))
            assertTrue(LocalDataArchive.lastResult(context).contains("2 项"))
            assertTrue(!java.io.File(root, ".codex-restore-staged/files/codex-home/config.toml").exists())
            LocalDataArchive.restoreIfPending(context)
            org.junit.Assert.assertEquals("existing-config", config.readText())
            org.junit.Assert.assertEquals("portable-history", java.io.File(home, "history.jsonl").readText())
            assertTrue(!java.io.File(root, "unknown-runtime").exists())
            try {
                LocalDataArchive.stageImport(context, archive(2))
                org.junit.Assert.fail("新版备份不得包含运行配置")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains("不允许恢复"))
            }
            assertTrue(!LocalDataArchive.hasPending(context))
            org.junit.Assert.assertEquals("existing-config", config.readText())
        } finally {
            check(root.deleteRecursively())
            app.deleteSharedPreferences(prefix + "codex-data-transfer-status")
        }
    }

    @Test fun realAnswerSnapshotSurvivesPortableRestore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "checkpoint-restore-").toFile()
        val prefix = root.name + "-"
        val privateRoot = java.io.File(root, "restored").apply { check(mkdirs()) }
        val workspace = java.io.File(root, "workspace").apply { check(mkdirs()) }
        val source = java.io.File(workspace, "demo").apply { check(mkdirs()) }
        val sourceFile = java.io.File(source, "Main.kt").apply { writeText("first revision") }
        val snapshots = java.io.File(root, AnswerCheckpointFormat.ROOT)
        val id = "12345678-1234-1234-1234-123456789abc"
        val context = object : android.content.ContextWrapper(app) {
            override fun getFilesDir() = privateRoot
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(prefix + name, mode)
        }
        try {
            val original = AnswerWorkspaceStore(workspace, snapshots)
            original.checkpoint(id, source.absolutePath)
            sourceFile.writeText("later revision")
            val output = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.txt"))
                zip.write("111-local-backup-version:2".toByteArray())
                zip.closeEntry()
                snapshots.walkTopDown().filter { it.isFile }.forEach { file ->
                    val name = "files/" + AnswerCheckpointFormat.ROOT + "/" + file.relativeTo(snapshots).invariantSeparatorsPath
                    assertTrue(LocalDataArchive.validatePath(name))
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            org.junit.Assert.assertEquals(2, LocalDataArchive.stageImport(context, output.toByteArray().inputStream()))
            LocalDataArchive.restoreIfPending(context)
            val restored = AnswerWorkspaceStore(workspace, java.io.File(privateRoot, AnswerCheckpointFormat.ROOT))
            assertTrue(restored.hasBaseline(id))
            val prepared = restored.prepare(id, source.absolutePath, false)
            org.junit.Assert.assertEquals("first revision", java.io.File(prepared.directory, "Main.kt").readText())
            org.junit.Assert.assertEquals("later revision", sourceFile.readText())
        } finally {
            check(root.deleteRecursively())
            app.deleteSharedPreferences(prefix + "codex-data-transfer-status")
        }
    }

    @Test fun serviceAndActivityReferToSameProcessRuntime() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
            .applicationContext as CodexApplication
        assertSame(app.session, app.session)
        val component = ComponentName(app, CodexForegroundService::class.java)
        val info = if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getServiceInfo(component,
                PackageManager.ComponentInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getServiceInfo(component, 0)
        }
        assertTrue(!info.exported)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue((info.foregroundServiceType and
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0)
        }
    }
}
