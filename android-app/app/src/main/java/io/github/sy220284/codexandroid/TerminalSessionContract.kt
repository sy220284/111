package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/** A command is confirmed in the UI before this protocol request is sent. */
object TerminalSessionContract {
    /** Android currently runs the core under the app UID. This policy is a
     * protocol declaration, not a verified OS-level sandbox. */
    private fun sandbox(networkEnabled: Boolean) = JSONObject()
        .put("type", "externalSandbox")
        .put("networkAccess", if (networkEnabled) "enabled" else "restricted")

    internal const val ACCESS_NOTICE = "终端与本机运行核心共享应用权限，可访问工作区外的应用可读写文件；请仅运行可信命令。"

    /** Fixed, user-initiated tool probe: no caller-supplied script, no stdin. */
    internal fun developerProbe(cwd: String): JSONObject {
        require(cwd.isNotBlank()) { "工作目录不可为空" }
        val script = listOf(
            "for name in git rg jq curl python3 node npm pip; do",
            "  if command -v \"\$name\" >/dev/null 2>&1; then",
            "    output=\$(\"\$name\" --version 2>&1); code=\$?",
            "    output=\$(printf '%s\\n' \"\$output\" | head -n 1)",
            "    printf '%s\\t%s\\t%s\\n' \"\$name\" \"\$code\" \"\$output\"",
            "  else",
            "    printf '%s\\t127\\t命令未找到\\n' \"\$name\"",
            "  fi",
            "done",
            "output=\$(python3 -m pip --version 2>&1); code=\$?",
            "printf '%s\\t%s\\t%s\\n' 'pip (python3 -m pip)' \"\$code\" \"\$output\"",
            "if command -v git-remote-https >/dev/null 2>&1; then",
            "  printf '%s\\t0\\t%s\\n' 'Git HTTPS' '传输组件已注册，连接待验证'",
            "else",
            "  printf '%s\\t127\\t%s\\n' 'Git HTTPS' '传输组件未安装'",
            "fi"
        ).joinToString("\n")
        return JSONObject()
            .put("command", JSONArray().put("/system/bin/sh").put("-c").put(script))
            .put("cwd", cwd)
            .put("timeoutMs", 30000)
            .put("sandboxPolicy", sandbox(false))
    }

    fun execute(command: String, cwd: String, processId: String, tty: Boolean = false, networkEnabled: Boolean = false): JSONObject {
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
            .put("sandboxPolicy", sandbox(networkEnabled))
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
    val error: String? = null,
    val workspace: String? = null
) {
    companion object {
        const val MAX_STREAM_BYTES = 128 * 1024

        fun start(processId: String, command: String, tty: Boolean = false, workspace: String? = null) =
            TerminalSessionState(processId = processId, command = command,
                tty = tty, running = true, workspace = workspace)
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
