package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.DataOutputStream
import java.io.OutputStreamWriter

/**
 * Newline-delimited JSON-RPC framing shared by native Codex and injected
 * Process-based tests. One owner reads stdout, and the caller serializes
 * frame order using its write scheduler. This channel owns writer teardown.
 */
internal class RpcProcessChannel(child: Process) {
    private val input = child.inputStream.bufferedReader(Charsets.UTF_8)
    private val stdin = child.outputStream
    private val output = BufferedWriter(OutputStreamWriter(stdin, Charsets.UTF_8))
    private val writerLock = Any()
    private val diagnosticPrefix = Regex("""^\[(?:TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\](?:\s|$)""", RegexOption.IGNORE_CASE)

    /** No loss or line coalescing: one valid JSON-RPC object per input line. */
    suspend fun readFrames(onNonProtocolLine: () -> Unit = {}, consume: suspend (JSONObject) -> Unit) {
        withContext(Dispatchers.IO) {
            input.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val frame = try {
                        JSONObject(line)
                    } catch (_: JSONException) {
                        val text = line.trimStart()
                        // A stdout diagnostic has a line boundary. A broken JSON frame
                        // may contain a reply or terminal event and cannot be discarded.
                        if (text.startsWith('{') || (text.startsWith('[') && !diagnosticPrefix.containsMatchIn(text))) {
                            throw JSONException("内核输出包含损坏的 JSON-RPC 帧")
                        }
                        if (text.isNotEmpty()) onNonProtocolLine()
                        continue
                    }
                    consume(frame)
                }
            }
        }
    }

    fun sendBootstrap(bytes: ByteArray, permit: () -> Boolean) = synchronized(writerLock) {
        if (!permit()) throw RpcWriteRevoked()
        DataOutputStream(stdin).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun send(message: JSONObject, permit: () -> Boolean = { true }) {
        val frame = message.toString()
        synchronized(writerLock) {
            if (!permit()) throw RpcWriteRevoked()
            output.write(frame)
            output.newLine()
            output.flush()
        }
    }

    fun closeWriter() = synchronized(writerLock) { output.close() }
}
