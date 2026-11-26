package io.github.sy220284.codexandroid

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Protect the self-update -> system installer -> Open handoff.
 * The currently running application is replaced during installation; the
 * installer must never depend on that application's activity task.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateInstallerIntentInstrumentedTest {
    @Test
    fun systemInstallerSurvivesReplacingTheCallingApplication() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse(
            "content://${app.packageName}.updateprovider/verified_updates/update.apk"
        )
        val intent = AppUpdateManager.systemInstallIntent(uri)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(uri, intent.data)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    @Test
    fun postInstallOpenResolvesToTheRealLauncherActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertNotNull("Installed app must expose a normal launcher to the installer", launch)
        assertEquals(ComponentName(app, MainActivity::class.java), launch!!.component)
        val home = app.packageManager.getActivityInfo(launch.component!!, 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, home.launchMode)
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertTrue(launch.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
    }
    @Test
    fun updateProviderSharesOnlyTheVerifiedUpdateCache() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val updateDir = File(app.cacheDir, "updates")
        assertTrue(updateDir.isDirectory || updateDir.mkdirs())
        val fixture = File(updateDir, "uri-permission-test.apk")
        fixture.writeText("verified fixture")
        val authority = "${app.packageName}.updateprovider"
        try {
            val uri = FileProvider.getUriForFile(app, authority, fixture)
            assertEquals("content", uri.scheme)
            assertEquals(authority, uri.authority)
            assertTrue(uri.path?.startsWith("/verified_updates/") == true)
            app.contentResolver.openInputStream(uri).use { stream ->
                assertNotNull("Update FileProvider must serve the real cache path", stream)
                assertEquals("verified fixture", stream!!.bufferedReader().readText())
            }
            val composerAuthority = "${app.packageName}.composerprovider"
            val updateInfo = app.packageManager.resolveContentProvider(authority, 0)!!
            val composerInfo = app.packageManager.resolveContentProvider(composerAuthority, 0)!!
            assertEquals(UpdateFileProvider::class.java.name, updateInfo.name)
            assertEquals(ComposerFileProvider::class.java.name, composerInfo.name)
            assertFalse(updateInfo.exported)
            assertFalse(composerInfo.exported)
            assertTrue(updateInfo.grantUriPermissions && composerInfo.grantUriPermissions)
            val cameraDir = File(app.cacheDir, "codex-camera").apply { check(isDirectory || mkdirs()) }
            val media = File(cameraDir, "provider-isolation-" + java.util.UUID.randomUUID() + ".png")
            try {
                media.writeText("composer fixture")
                val cameraUri = FileProvider.getUriForFile(app, composerAuthority, media)
                assertEquals("image/png", app.contentResolver.getType(cameraUri))
                app.contentResolver.query(cameraUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals(media.name, it.getString(0))
                }
                assertEquals("composer fixture", app.contentResolver.openInputStream(cameraUri)!!.bufferedReader().use { it.readText() })
                assertEquals("verified fixture", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
                for ((foreignAuthority, file) in listOf(authority to media, composerAuthority to fixture)) {
                    try {
                        FileProvider.getUriForFile(app, foreignAuthority, file)
                        org.junit.Assert.fail("Provider must reject a path owned by the other authority")
                    } catch (_: IllegalArgumentException) { }
                }
            } finally { media.delete() }
            val intent = AppUpdateManager.systemInstallIntent(uri)
            assertEquals(uri, intent.data)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

            try {
                FileProvider.getUriForFile(app, authority, File(app.cacheDir, "outside-updates.apk"))
                org.junit.Assert.fail("FileProvider must not expose arbitrary cache files")
            } catch (_: IllegalArgumentException) {
                // Only cache/updates is declared in update_paths.xml.
            }
        } finally {
            fixture.delete()
        }
    }

}
