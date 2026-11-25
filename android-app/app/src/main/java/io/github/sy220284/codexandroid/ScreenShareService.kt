package io.github.sy220284.codexandroid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** Local live capture, no automatic network/model requests. Only explicitly attached frames leave memory. */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var token: String? = null
    private var lastSample = 0L
    private var ending = false
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { terminate("屏幕共享已由系统结束") }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && display != null && !ending) {
                try { resize(width, height) }
                catch (_: Exception) { terminate("共享画面尺寸调整失败，请重新共享") }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { terminate(); return START_NOT_STICKY }
        val requested = intent?.getStringExtra(TOKEN)
        if (requested == null || screenShareSession.state.value.token != requested) {
            stopSelf(); return START_NOT_STICKY
        }
        if (token != null) return START_NOT_STICKY
        token = requested
        try {
            promote()
            @Suppress("DEPRECATION")
            val consent = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(CONSENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(CONSENT)
            requireNotNull(consent) { "屏幕共享授权无效" }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val capture = manager.getMediaProjection(Activity.RESULT_OK, consent)
            projection = capture
            capture.registerCallback(callback, main)
            worker = HandlerThread("codex-screen-capture").apply { start() }
            val metrics = resources.displayMetrics
            val bounds = if (Build.VERSION.SDK_INT >= 30)
                getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else null
            val dimensions = bounded(bounds?.width() ?: metrics.widthPixels, bounds?.height() ?: metrics.heightPixels)
            val imageReader = newReader(dimensions.first, dimensions.second)
            reader = imageReader
            display = capture.createVirtualDisplay("Codex screen share", dimensions.first, dimensions.second,
                metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, main)
            check(screenShareSession.started(requested)) { "屏幕共享会话已变化" }
        } catch (_: Exception) {
            terminate("屏幕共享启动失败，请重新授权")
        }
        return START_NOT_STICKY
    }

    private fun promote() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Codex 屏幕共享", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, ID, Intent(this, ScreenShareService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val note = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_codex_status)
            .setContentTitle("正在共享屏幕").setContentText("画面仅在本机采集，手动添加后随消息发送")
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(R.drawable.ic_codex_stop, "停止共享", stop).build()).build()
        startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun bounded(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(1.0, 1600.0 / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    private fun resize(width: Int, height: Int) {
        val (w, h) = bounded(width, height)
        val old = reader ?: return
        if (w == old.width && h == old.height) return
        val next = newReader(w, h)
        try {
            token?.let(screenShareSession::invalidate)
            display?.resize(w, h, resources.displayMetrics.densityDpi)
            display?.setSurface(next.surface)
            reader = next
            old.close()
        } catch (failure: Exception) { next.close(); throw failure }
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        val result = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val sessionToken = checkNotNull(token)
        result.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSample < 750L) return@use
                    lastSample = now
                    val plane = image.planes[0]
                    val stride = plane.rowStride / plane.pixelStride
                    require(plane.pixelStride == 4 && stride >= image.width && stride <= image.width + 1024)
                    val padded = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
                    try {
                        plane.buffer.rewind()
                        padded.copyPixelsFromBuffer(plane.buffer)
                        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        try {
                            val bytes = ByteArrayOutputStream().use { stream ->
                                check(cropped.compress(Bitmap.CompressFormat.JPEG, 88, stream))
                                stream.toByteArray()
                            }
                            try { screenShareSession.publish(sessionToken, bytes) } finally { bytes.fill(0) }
                        } finally { if (cropped !== padded) cropped.recycle() }
                    } finally { padded.recycle() }
                }
            } catch (_: IllegalStateException) {
                // A queued callback may race a reader's release. Report only if this reader still owns capture.
                main.post { if (reader === source && !ending) terminate("共享画面读取失败，请重新共享") }
            } catch (_: Exception) {
                main.post { if (reader === source && !ending) terminate("共享画面解析失败，请重新共享") }
            }
        }, Handler(checkNotNull(worker).looper))
        return result
    }

    private fun terminate(message: String = "") {
        if (ending) return
        ending = true
        token?.let { screenShareSession.finish(it, message) }
        stopSelf()
    }

    override fun onDestroy() {
        ending = true
        token?.let { screenShareSession.finish(it) }
        try { display?.release() } finally {
            try { reader?.close() } finally {
                projection?.unregisterCallback(callback)
                projection?.stop()
                worker?.quitSafely()
                stopForeground(STOP_FOREGROUND_REMOVE)
                super.onDestroy()
            }
        }
    }

    companion object {
        private const val CHANNEL = "codex-screen-share"
        private const val ID = 1102
        private const val STOP = "io.github.sy220284.codexandroid.STOP_SCREEN_SHARE"
        private const val TOKEN = "screen_token"
        private const val CONSENT = "screen_consent"

        internal fun start(context: Context, token: String, consent: Intent) {
            check(screenShareSession.state.value.token == token)
            ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java)
                .putExtra(TOKEN, token).putExtra(CONSENT, consent))
        }
        internal fun stop(context: Context) {
            screenShareSession.state.value.token?.let { screenShareSession.finish(it) }
            context.stopService(Intent(context, ScreenShareService::class.java))
        }
    }
}
