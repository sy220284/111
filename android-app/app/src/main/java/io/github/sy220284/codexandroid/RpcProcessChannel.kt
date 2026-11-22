package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Newline-delimited JSON-RPC framing shared by native Codex and injected
 * Process-based tests. One owner reads stdout, and the caller serializes
 * writes with its existing writeMutex.
 */
internal class RpcProcessChannel(child: Process) {
    private val input = child.inputStream.bufferedReader(Charsets.UTF_8)
    private val output = BufferedWriter(OutputStreamWriter(child.outputStream, Charsets.UTF_8))

    /** No loss or line coalescing: one valid JSON-RPC object per input line. */
    suspend fun readFrames(consume: suspend (JSONObject) -> Unit) {
        withContext(Dispatchers.IO) {
            input.use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    consume(JSONObject(line))
                }
            }
        }
    }

    fun send(message: JSONObject) {
        output.write(message.toString())
        output.newLine()
        output.flush()
    }

    fun closeWriter() = output.close()
}
