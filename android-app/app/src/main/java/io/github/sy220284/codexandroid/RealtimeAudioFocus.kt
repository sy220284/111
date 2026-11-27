package io.github.sy220284.codexandroid

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns one system audio-focus request; retired callbacks cannot interrupt a new session.
 * The realtime controller retains ownership of the media and native stop lifecycle. */
internal class RealtimeAudioFocus(
    private val manager: AudioManager?,
    private val attributes: AudioAttributes
) {
    private var active: AudioFocusRequest? = null

    fun acquire(onInterrupted: () -> Unit) {
        check(active == null) { "实时语音已持有音频焦点" }
        val audio = checkNotNull(manager) { "此设备没有可用的音频服务" }
        lateinit var request: AudioFocusRequest
        request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && active === request) onInterrupted()
            }, Handler(Looper.getMainLooper()))
            .build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "暂时无法使用音频，请先结束正在进行的通话或播放"
        }
        active = request
    }

    fun release() {
        val request = active ?: return
        active = null
        if (manager?.abandonAudioFocusRequest(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w("RealtimeVoice", "Android did not confirm audio focus release")
        }
    }
}
