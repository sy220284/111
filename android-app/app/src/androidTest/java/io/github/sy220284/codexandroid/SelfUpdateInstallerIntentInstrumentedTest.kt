package io.github.sy220284.codexandroid

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
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
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
        assertFalse(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
    }

    @Test
    fun postInstallOpenResolvesToTheRealLauncherActivity() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        assertNotNull("Installed app must expose a normal launcher to the installer", launch)
        assertEquals(ComponentName(app, MainActivity::class.java), launch!!.component)
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertTrue(launch.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)
    }
}
