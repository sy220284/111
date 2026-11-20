package io.github.sy220284.codexandroid

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs in an actual Android emulator: starts the packaged executable and
 * negotiates a real JSON-RPC session, without requiring the user's login.
 */
@RunWith(AndroidJUnit4::class)
class CoreStartupInstrumentedTest {
    @Test fun bundledCoreInitializesAndAnswersAccountRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val bridge = CodexConnection(app)
        try {
            withTimeout(90_000L) {
                bridge.connect()
                assertEquals(EngineStatus.Connected, bridge.status.value)
                val response = bridge.request("account/read")
                assertNotNull(response)
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "Android Codex core start failed. ABI=" + Build.SUPPORTED_ABIS.joinToString() +
                "; status=" + bridge.status.value +
                "; recentStderr=" + bridge.recentStderr.value.joinToString(" / "),
                failure
            )
        } finally {
            bridge.close()
        }
    }
}
