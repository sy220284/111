package io.github.sy220284.codexandroid

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android audio service, not a mock and not a cloud voice session. */
@RunWith(AndroidJUnit4::class)
class RealtimeAudioFocusInstrumentedTest {
    @Test fun competingAudioFocusInterruptsAndAllowsNewSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val manager = requireNotNull(app.getSystemService(AudioManager::class.java))
        val voice = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val owner = RealtimeAudioFocus(manager, voice)
        val interrupted = CountDownLatch(1)
        val competitor = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ }, Handler(Looper.getMainLooper()))
            .build()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    owner.acquire {
                        owner.release()
                        interrupted.countDown()
                    }
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(competitor))
                }
                assertTrue("Android did not dispatch competing audio focus loss", interrupted.await(10, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.abandonAudioFocusRequest(competitor))
                    owner.acquire { }
                    owner.release()
                }
            } finally {
                scenario.onActivity {
                    manager.abandonAudioFocusRequest(competitor)
                    owner.release()
                }
            }
        }
    }
}
