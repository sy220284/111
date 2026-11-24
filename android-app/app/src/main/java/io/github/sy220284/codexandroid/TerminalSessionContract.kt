package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A command is confirmed in the UI before this protocol request is sent. */
object TerminalSessionContract {
    fun execute(command: String, cwd: String, processId: String, tty: Boolean = false): JSONObject {
        require(command.isNotBlank()) { "请输入要执行的命令" }
        require(cwd.isNotBlank()) { "工作目录不可为空" }
        require(processId.isNotBlank()) { "缺少终端会话标识" }
        return JSONObject()
            .put("command", JSONArray().put("/system/bin/sh").put("-c").put(command))
            .put("cwd", cwd)
            .put("processId", processId)
            .put("streamStdoutStderr", true)
            .put("streamStdin", true)
            .put("disableTimeout", true)
            .apply {
                if (tty) {
                    put("tty", true)
                    put("size", JSONObject().put("rows", 24).put("cols", 80))
                }
            }
            .put("sandboxPolicy", JSONObject()
                .put("type", "externalSandbox")
                .put("networkAccess", "restricted"))
    }

    fun write(processId: String, line: String): JSONObject {
        require(processId.isNotBlank()) { "无可用终端会话" }
        require(line.isNotEmpty()) { "请输入终端输入内容" }
        return JSONObject()
            .put("processId", processId)
            .put("deltaBase64", Base64.getEncoder().encodeToString(
                (line + "\n").toByteArray(StandardCharsets.UTF_8)))
            .put("closeStdin", false)
    }

    fun resize(processId: String, rows: Int, cols: Int): JSONObject {
        require(processId.isNotBlank()) { "需要正在运行的终端会话" }
        require(rows in 2..300 && cols in 10..400) { "终端行列数不在有效范围内" }
        return JSONObject().put("processId", processId)
            .put("size", JSONObject().put("rows", rows).put("cols", cols))
    }

    fun terminate(processId: String): JSONObject {
        require(processId.isNotBlank()) { "无可用终端会话" }
        return JSONObject().put("processId", processId)
    }
}

/**
 * Each notification belongs to one client-created process id. The raw UTF-8
 * bytes are kept until decoding so a Unicode character split between chunks
 * is not damaged. A rolling per-stream limit preserves recent logs without unbounded UI memory.
 */
data class TerminalSessionState(
    val processId: String = "",
    val command: String = "",
    val tty: Boolean = false,
    val running: Boolean = false,
    val completed: Boolean = false,
    val terminating: Boolean = false,
    val stdoutBytes: ByteArray = byteArrayOf(),
    val stderrBytes: ByteArray = byteArrayOf(),
    val outputTruncated: Boolean = false,
    val exitCode: Int? = null,
    val error: String? = null
) {
    companion object {
        const val MAX_STREAM_BYTES = 128 * 1024

        fun start(processId: String, command: String, tty: Boolean = false) =
            TerminalSessionState(processId = processId, command = command,
                tty = tty, running = true)
    }

    val stdoutText: String get() = String(stdoutBytes, StandardCharsets.UTF_8)
    val stderrText: String get() = String(stderrBytes, StandardCharsets.UTF_8)

    fun append(params: JSONObject): TerminalSessionState {
        if (!running || params.optString("processId") != processId) return this
        val stream = params.getString("stream")
        require(stream == "stdout" || stream == "stderr") { "终端输出类型不支持：$stream" }
        val chunk = Base64.getDecoder().decode(params.getString("deltaBase64"))
        val previous = if (stream == "stdout") stdoutBytes else stderrBytes
        val total = previous.size.toLong() + chunk.size
        val truncated = outputTruncated || total > MAX_STREAM_BYTES ||
            params.optBoolean("capReached")
        // Copy only the retained tail; avoid concatenating and trimming twice.
        val keptSize = minOf(total, MAX_STREAM_BYTES.toLong()).toInt()
        val fromChunk = minOf(chunk.size, keptSize)
        val fromPrevious = keptSize - fromChunk
        val kept = ByteArray(keptSize)
        previous.copyInto(kept, 0, previous.size - fromPrevious, previous.size)
        chunk.copyInto(kept, fromPrevious, chunk.size - fromChunk, chunk.size)
        return if (stream == "stdout") copy(stdoutBytes = kept, outputTruncated = truncated)
        else copy(stderrBytes = kept, outputTruncated = truncated)
    }

    fun finish(result: JSONObject): TerminalSessionState {
        var next = this
        // Streaming responses normally have empty strings. Preserve any
        // final buffered content instead of silently discarding it.
        for (stream in listOf("stdout", "stderr")) {
            val tail = result.optString(stream)
            if (tail.isNotEmpty()) {
                next = next.append(JSONObject()
                    .put("processId", processId)
                    .put("stream", stream)
                    .put("deltaBase64", Base64.getEncoder()
                        .encodeToString(tail.toByteArray(StandardCharsets.UTF_8)))
                    .put("capReached", false))
            }
        }
        return next.copy(
            running = false, completed = true, terminating = false,
            exitCode = if (result.has("exitCode")) result.optInt("exitCode") else null
        )
    }

    fun fail(message: String) = copy(
        running = false, completed = true, terminating = false, error = message
    )
}
