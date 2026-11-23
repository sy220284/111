package io.github.sy220284.codexandroid

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
        val source = File(context.filesDir, "ci-self-update.apk")
        assertTrue("Missing self-update APK fixture: ${source.absolutePath}", source.isFile)
        // The application has already completed its startup cache sweep.
        // Copy the fixture into a fresh staging directory, then protect it
        // with the same marker the release updater writes before handoff.
        val currentCode =
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val root = UpdateCache.prepare(
            context.cacheDir, currentCode, System.currentTimeMillis()
        )
        val fixture = File(root, "ci-self-update.apk")
        source.copyTo(fixture)
        assertTrue("Could not clear the pre-staging APK", source.delete())
        // The CI installs the same debug APK (same versionCode), so use a
        // synthetic future target solely to test protection across restart.
        UpdateCache.markInstallerHandoff(
            root, fixture, currentCode + 1, System.currentTimeMillis()
        )
        // Dedicated instrumentation invocation: keep the source Activity alive until
        // the system installer's separate task takes over. Closing ActivityScenario
        // immediately after startActivity races the asynchronous installer launch and
        // may return the emulator to the launcher before the installer becomes visible.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val uri = FileProvider.getUriForFile(
                activity.applicationContext, "${activity.packageName}.updateprovider", fixture
            )
            activity.applicationContext.startActivity(AppUpdateManager.systemInstallIntent(uri))
        }
        // startActivity returns before the remote installer completes its launch.
        // Verify the actual system confirmation UI before ending instrumentation,
        // otherwise runner teardown may send us to Launcher before it appears.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 15_000
        var installerReady = false
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && isInstallerConfirmationVisible(root)) {
                installerReady = true
                break
            }
            Thread.sleep(250)
        }
        assertTrue("PackageInstaller confirmation did not reach foreground", installerReady)
        // The host replaces the package, terminating the source activity.
        // This destructive test is run separately so it leaves no state behind.
    }
    private fun isInstallerConfirmationVisible(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf("Install", "Update", "安装", "更新")
        return labels.any { expected ->
            root.findAccessibilityNodeInfosByText(expected).any { node ->
                node.isVisibleToUser && node.isEnabled &&
                    (node.text?.toString()?.equals(expected, ignoreCase = true) == true ||
                        node.contentDescription?.toString()
                            ?.equals(expected, ignoreCase = true) == true)
            }
        }
    }

}
