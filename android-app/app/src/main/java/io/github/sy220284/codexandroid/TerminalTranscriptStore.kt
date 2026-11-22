package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Immutable window of historical stdout, read on demand from the local transcript. */
internal data class TerminalTranscriptSlice(
    val text: String, val beforeOffset: Long, val hasMore: Boolean
)

/**
 * Stores ordered stdout/stderr bytes privately on the device. The UI continues
 * to retain only a 128 KiB sliding window; the disk quota protects device storage.
 */
internal class TerminalTranscriptStore(
    private val directory: File,
    private val maxStoredBytes: Long = 64L * 1024 * 1024
) {
    private val lock = Mutex()
    private var activeProcessId: String? = null
    private var usedBytes: Long = 0
    val available = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val stdout get() = File(directory, "stdout.bin")
    private val stderr get() = File(directory, "stderr.bin")

    suspend fun begin(processId: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(processId.isNotBlank()) { "终端进程 ID 不能为空" }
            activeProcessId = null
            available.value = false
            error.value = null
            try {
                if (!directory.isDirectory && !directory.mkdirs())
                    throw IOException("无法创建本机终端记录目录")
                stdout.outputStream().use { }
                stderr.outputStream().use { }
                usedBytes = 0
                activeProcessId = processId
                available.value = true
            } catch (failure: IOException) {
                error.value = "本机终端日志未开始保存：" + failure.message
            }
        }
    }

    suspend fun appendNotification(params: JSONObject) {
        val processId = params.optString("processId")
        val stream = params.getString("stream")
        val bytes = Base64.getDecoder().decode(params.getString("deltaBase64"))
        append(processId, stream, bytes)
    }

    suspend fun appendTail(processId: String, result: JSONObject) {
        for (stream in listOf("stdout", "stderr")) {
            val tail = result.optString(stream)
            if (tail.isNotEmpty())
                append(processId, stream, tail.toByteArray(StandardCharsets.UTF_8))
        }
    }

    suspend fun append(processId: String, stream: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(stream == "stdout" || stream == "stderr") {
                    "无法保存未知类型的终端输出：" + stream
                }
                if (!available.value || activeProcessId != processId || bytes.isEmpty())
                    return@withLock
                val room = (maxStoredBytes - usedBytes).coerceAtLeast(0)
                    .coerceAtMost(bytes.size.toLong()).toInt()
                try {
                    if (room > 0) {
                        (if (stream == "stdout") stdout else stderr).appendBytes(
                            if (room == bytes.size) bytes else bytes.copyOfRange(0, room))
                        usedBytes += room
                    }
                    if (room < bytes.size)
                        error.value = "本机日志已达到 " +
                            (maxStoredBytes / (1024 * 1024)) +
                            "MiB 上限；超出的内容不会存档"
                } catch (failure: IOException) {
                    available.value = false
                    error.value = "本机终端日志保存中断：" + failure.message
                }
            }
        }

    /**
     * Page backwards through stdout. Both boundaries align to the beginning
     * of a UTF-8 character, so adjacent pages do not corrupt multilingual logs.
     * The default upper bound excludes the 128KiB already visible in the live UI.
     */
    suspend fun readStdoutBefore(
        processId: String,
        beforeOffset: Long? = null,
        visibleTailBytes: Int = 128 * 1024,
        pageBytes: Int = 32 * 1024
    ): TerminalTranscriptSlice = withContext(Dispatchers.IO) {
        require(pageBytes in 1..65536 && visibleTailBytes >= 0) { "日志读取范围无效" }
        lock.withLock {
            check(available.value && activeProcessId == processId) {
                "当前命令记录已切换，请返回终端重试"
            }
            RandomAccessFile(stdout, "r").use { file ->
                val length = file.length()
                fun alignBefore(position: Long): Long {
                    var at = position.coerceIn(0, length)
                    if (at >= length) return at
                    while (at > 0) {
                        file.seek(at)
                        val byte = file.readUnsignedByte()
                        if ((byte and 0xC0) != 0x80) break
                        at--
                    }
                    return at
                }
                val desiredEnd = beforeOffset ?: (length - visibleTailBytes).coerceAtLeast(0)
                val end = alignBefore(desiredEnd)
                if (end == 0L) return@withLock TerminalTranscriptSlice("", 0, false)
                val start = alignBefore((end - pageBytes).coerceAtLeast(0))
                val size = (end - start).toInt()
                val bytes = ByteArray(size)
                file.seek(start)
                file.readFully(bytes)
                TerminalTranscriptSlice(
                    String(bytes, Charsets.UTF_8), start, start > 0
                )
            }
        }
    }

    /** Streams the transcript to a user-chosen file without loading it all into RAM. */
    suspend fun export(processId: String, command: String, output: OutputStream) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                check(available.value && activeProcessId == processId) {
                    "本次命令的终端日志已失效，请返回终端重新核对"
                }
                output.write(("命令：" + command + "\n\n===== 标准输出 =====\n")
                    .toByteArray(StandardCharsets.UTF_8))
                stdout.inputStream().use { it.copyTo(output) }
                output.write("\n\n===== 错误输出 =====\n".toByteArray(StandardCharsets.UTF_8))
                stderr.inputStream().use { it.copyTo(output) }
                if (error.value != null)
                    output.write(("\n\n[注意：" + error.value + "]\n")
                        .toByteArray(StandardCharsets.UTF_8))
            }
        }
}
