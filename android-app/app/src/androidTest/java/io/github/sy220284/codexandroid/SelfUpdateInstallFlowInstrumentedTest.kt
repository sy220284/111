package io.github.sy220284.codexandroid

import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Entry point for the host-driven self-update integration test.
 * The host installs the currently running package again, then presses
 * the system installer's own "Open" button after this test exits.
 * This test must be invoked separately from the normal screenshot suite.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateInstallFlowInstrumentedTest {
    @Test
    fun openSystemInstallerFromTheAppBeingUpdated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "updates/ci-self-update.apk")
        assertTrue("Missing self-update APK fixture: ${fixture.absolutePath}", fixture.isFile)
        // Dedicated instrumentation invocation: keep the source Activity alive until
        // the system installer's separate task takes over. Closing ActivityScenario
        // immediately after startActivity races the asynchronous installer launch and
        // may return the emulator to the launcher before the installer becomes visible.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.updateprovider", fixture
            )
            activity.startActivity(AppUpdateManager.systemInstallIntent(uri))
        }
        // The host-side flow replaces this package, terminating the source activity.
        // This test is run by itself, so no subsequent test inherits its activity.
    }
}
