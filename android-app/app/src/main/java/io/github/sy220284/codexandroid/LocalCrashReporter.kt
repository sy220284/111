package io.github.sy220284.codexandroid

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant

/** Local-only, content-free uncaught crash journal, never forwarded to a network. */
internal object LocalCrashReporter {
    private const val MAX_BYTES = 24_000
    private var installed = false
    private val lock = Any()
    private fun file(context: Context): File =
        File(context.filesDir, "diagnostics/local-crashes.log")

    fun format(throwable: Throwable, atMillis: Long): String {
        val frames = throwable.stackTrace.take(12).joinToString("\n") { frame ->
            "  " + frame.className + "." + frame.methodName + ":" + frame.lineNumber
        }
        return Instant.ofEpochMilli(atMillis).toString() + " | " +
            throwable.javaClass.name + "\n" + frames
    }

    fun read(context: Context): String = synchronized(lock) {
        val log = file(context)
        if (log.isFile) log.readText(Charsets.UTF_8).takeLast(MAX_BYTES) else ""
    }

    fun clear(context: Context) = synchronized(lock) {
        val log = file(context)
        if (log.exists() && !log.delete()) throw IOException("无法清除本机崩溃记录")
    }

    fun install(context: Context) = synchronized(lock) {
        if (installed) return@synchronized
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                synchronized(lock) {
                    val log = file(app)
                    if (!log.parentFile.isDirectory && !log.parentFile.mkdirs())
                        throw IOException("无法创建本机诊断目录")
                    val entry = format(failure, System.currentTimeMillis()) + "\n\n"
                    val old = if (log.isFile) log.readText(Charsets.UTF_8).takeLast(MAX_BYTES / 2) else ""
                    log.writeText((old + entry).takeLast(MAX_BYTES), Charsets.UTF_8)
                }
            } catch (diagnosticFailure: IOException) {
                android.util.Log.e("CodexCrashJournal", "本机诊断保存失败", diagnosticFailure)
            } finally {
                previous?.uncaughtException(thread, failure)
            }
        }
        installed = true
    }
}
