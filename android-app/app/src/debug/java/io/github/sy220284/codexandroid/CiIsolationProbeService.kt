package io.github.sy220284.codexandroid

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Debug-only experiment: measure actual Android isolation, never execute caller supplied commands. */
class CiIsolationProbeService : Service() {
    @Suppress("DEPRECATION")
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.what != 1 || request.replyTo == null) return@Handler false
        val reply = Message.obtain(null, 2)
        val data = request.data
        data.classLoader = ParcelFileDescriptor::class.java.classLoader
        val path = data.getString("sentinel").orEmpty()
        val fd = data.getParcelable<ParcelFileDescriptor>("fd")
        val result = Bundle()
        result.putInt("uid", Process.myUid())
        result.putBoolean("privateRead", runCatching { File(path).readText(); true }.getOrDefault(false))
        result.putBoolean("privateWrite", runCatching { File(path).writeText("probe-write"); true }.getOrDefault(false))
        result.putBoolean("networkConnected", runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", data.getInt("port")), 1000) }
            true
        }.getOrDefault(false))
        result.putString("descriptor", runCatching {
            requireNotNull(fd)
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.bufferedReader().readText() }
        }.getOrElse { "unavailable:" + it.javaClass.simpleName })
        result.putString("shell", runCatching {
            val child = ProcessBuilder("/system/bin/sh", "-c", "printf isolation-probe").start()
            try {
                if (!child.waitFor(3, TimeUnit.SECONDS)) error("shell timed out")
                check(child.exitValue() == 0)
                child.inputStream.bufferedReader().use { it.readText() }
            } finally { child.destroyForcibly() }
        }.getOrElse { "unavailable:" + it.javaClass.simpleName })
        reply.data = result
        request.replyTo.send(reply)
        true
    })
    override fun onBind(intent: Intent?): IBinder = receiver.binder
}
