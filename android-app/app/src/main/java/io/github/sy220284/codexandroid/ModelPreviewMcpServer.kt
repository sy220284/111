package io.github.sy220284.codexandroid

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A single loopback-only, authenticated, stateless MCP transport. No file bytes are returned. */
internal class ModelPreviewMcpServer(
    private val onPreview: (JSONObject, JSONObject) -> JSONObject
) : NanoHTTPD("127.0.0.1", 0) {
    private val authorization = "Bearer " + UUID.randomUUID().toString() + UUID.randomUUID()
    private val runner = BoundedRunner()
    init { setAsyncRunner(runner) }

    fun launch() { start(SOCKET_READ_TIMEOUT, false) }
    fun config(): JSONObject = JSONObject()
        .put("url", "http://127.0.0.1:${listeningPort}/mcp")
        .put("http_headers", JSONObject().put("Authorization", authorization))
        .put("default_tools_approval_mode", "approve").put("startup_timeout_sec", 10)
        .put("tool_timeout_sec", 25)

    override fun serve(session: IHTTPSession): Response {
        fun reply(status: Response.Status, body: String = "", type: String = "text/plain") =
            newFixedLengthResponse(status, type, body).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Connection", "close")
                closeConnection(true)
            }
        if (session.uri != "/mcp") return reply(Response.Status.NOT_FOUND)
        val provided = session.headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(authorization.toByteArray(), provided.toByteArray()))
            return reply(Response.Status.UNAUTHORIZED)
        if (session.method != Method.POST) return reply(Response.Status.METHOD_NOT_ALLOWED)
        val length = session.headers["content-length"]?.toLongOrNull()
        if (length == null || length !in 1..65536 || session.headers.containsKey("transfer-encoding"))
            return reply(Response.Status.BAD_REQUEST, "Invalid request length")
        val request = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            JSONObject(files["postData"] ?: error("Missing body"))
        } catch (_: Exception) { return reply(Response.Status.BAD_REQUEST, "Invalid JSON") }
        val id = request.opt("id")
        if (request.optString("jsonrpc") != "2.0") return reply(Response.Status.BAD_REQUEST, "Invalid JSON-RPC")
        if (id == null || id == JSONObject.NULL)
            return reply(Response.Status.ACCEPTED)
        if (id !is String && id !is Number) return reply(Response.Status.BAD_REQUEST, "Invalid request id")
        val params = request.optJSONObject("params") ?: JSONObject()
        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id)
        val result = when (request.optString("method")) {
            "initialize" -> JSONObject().put("protocolVersion",
                params.optString("protocolVersion").takeIf { it in setOf("2025-03-26", "2025-06-18", "2025-11-25") } ?: "2025-06-18")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", NAME).put("version", "1.0"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", JSONArray().put(tool()))
            "tools/call" -> try {
                require(params.optString("name") == TOOL) { "Unknown tool" }
                val result = onPreview(params.optJSONObject("arguments") ?: JSONObject(),
                    params.optJSONObject("_meta") ?: JSONObject())
                JSONObject().put("isError", false).put("structuredContent", result)
                    .put("content", JSONArray().put(JSONObject().put("type", "text")
                        .put("text", "Preview queued for the user. This tool does not read or return file contents.")))
            } catch (_: Exception) {
                JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject()
                    .put("type", "text").put("text", "Preview unavailable: invalid target, thread, workspace, or queue capacity.")))
            }
            else -> null
        }
        if (result == null) envelope.put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
        else envelope.put("result", result)
        return reply(Response.Status.OK, envelope.toString(), "application/json")
    }

    private class BoundedRunner : AsyncRunner {
        private val handles = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val pool = ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS, ArrayBlockingQueue(16))
        override fun exec(handler: ClientHandler) {
            handles.add(handler)
            try { pool.execute(handler) }
            catch (_: RejectedExecutionException) { handles.remove(handler); handler.close() }
        }
        override fun closed(handler: ClientHandler) { handles.remove(handler) }
        override fun closeAll() {
            handles.toList().forEach { it.close() }
            handles.clear()
            pool.shutdownNow()
        }
    }

    companion object {
        const val NAME = "codex_android_preview"
        const val TOOL = "open_in_codex"
        fun tool() = JSONObject().put("name", TOOL)
            .put("description", "Show a file from the calling thread's workspace in Codex Android, with an optional one-based source line. The user opens the preview. Only queues UI presentation; use file tools to inspect content.")
            .put("inputSchema", JSONObject("""{"type":"object","additionalProperties":false,"required":["target"],"properties":{"target":{"type":"object","additionalProperties":false,"required":["type","path"],"properties":{"type":{"type":"string","const":"file"},"path":{"type":"string","minLength":1,"maxLength":4096},"line":{"type":"integer","minimum":1,"maximum":2147483647}}}}}"""))
    }
}
