package io.github.sy220284.codexandroid

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage the same signed APK for the destructive host-driven replacement test.
 * The normal application process opens the installer only AFTER instrumentation
 * has exited, so AndroidJUnitRunner cannot tear down its source Activity/task.
 */
@RunWith(AndroidJUnit4::class)
class SelfUpdateInstallFlowInstrumentedTest {
    @Test
    fun stageVerifiedSelfUpdateFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.filesDir, "ci-self-update.apk")
        assertTrue("Missing self-update APK fixture: ${source.absolutePath}", source.isFile)
        val currentCode =
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val root = UpdateCache.prepare(
            context.cacheDir, currentCode, System.currentTimeMillis()
        )
        val fixture = File(root, "ci-self-update.apk")
        source.copyTo(fixture)
        assertEquals(source.length(), fixture.length())
        assertTrue("Could not clear the pre-staging APK", source.delete())
        // The same-version fixture needs a synthetic future target only to
        // preserve staging when the test process exits and the normal app starts.
        UpdateCache.markInstallerHandoff(
            root, fixture, currentCode + 1, System.currentTimeMillis()
        )
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updateprovider", fixture
        )
        assertEquals(uri, AppUpdateManager.systemInstallIntent(uri).data)
        assertTrue(context.packageManager.canRequestPackageInstalls())
    }
}
