package io.github.sy220284.codexandroid

import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Preparation for isolated, explicitly selected Google Device Run ARM64 tests. */
internal object CloudArm64RuntimeEnvironment {
    fun enabled(): Boolean = InstrumentationRegistry.getArguments().getString("cloudArm64") == "true"

    fun prepare() {
        if (!enabled()) return
        assertEquals(InstrumentationRegistry.getArguments().getString("cloudApi")!!.toInt(), Build.VERSION.SDK_INT)
        assertEquals("arm64-v8a", Build.SUPPORTED_ABIS.first())
        assertTrue("64-bit application process required", Process.is64Bit())
        assertEquals("aarch64", Os.uname().machine)
        assertEquals(InstrumentationRegistry.getArguments().getString("cloudPageSize")!!.toLong(), Os.sysconf(OsConstants._SC_PAGESIZE))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Same dedicated-device workspace preparation as the local runtime CI.
        // No production permissions or authorization behavior is changed.
        if (!Environment.isExternalStorageManager()) {
            val pkg = instrumentation.targetContext.packageName
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("appops set $pkg MANAGE_EXTERNAL_STORAGE allow")
            ).use { it.readBytes() }
        }
        assertTrue("Cloud test workspace must have its isolated device authorization", Environment.isExternalStorageManager())
    }
}

@RunWith(AndroidJUnit4::class)
class CloudArm64DeviceInstrumentedTest {
    @Test fun nativeArm64Android17And16KiBPages() {
        assertTrue("Cloud-specific instrumentation argument missing", CloudArm64RuntimeEnvironment.enabled())
        CloudArm64RuntimeEnvironment.prepare()
    }
}
