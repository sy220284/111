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
    private var cloudActivity: androidx.test.core.app.ActivityScenario<MainActivity>? = null

    @org.junit.Before fun prepareCloudDevice() {
        CloudArm64RuntimeEnvironment.prepare()
        if (CloudArm64RuntimeEnvironment.enabled()) {
            cloudActivity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        }
    }

    @org.junit.After fun closeCloudActivity() {
        cloudActivity?.close()
        cloudActivity = null
    }

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
