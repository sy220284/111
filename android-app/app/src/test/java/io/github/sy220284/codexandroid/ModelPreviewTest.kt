package io.github.sy220284.codexandroid

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPreviewTest {
    @Test fun loopbackMcpSupportsHandshakeDiscoveryAndAuthenticatedCall() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val host = ModelPreviewMcpServer { args, meta ->
            calls.incrementAndGet()
            assertEquals("demo.txt" to 2, ModelPreviewPolicy.target(args))
            JSONObject().put("status", "queued").put("threadId", ModelPreviewPolicy.thread(meta))
        }
        host.launch()
        try {
            val config = host.config()
            val token = config.getJSONObject("http_headers").getString("Authorization")
            fun post(method: String, params: JSONObject, auth: String = token): Pair<Int, JSONObject?> {
                val connection = URL(config.getString("url")).openConnection() as HttpURLConnection
                return try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", auth)
                    val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
                        .toString().toByteArray()
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    code to if (code == 200) JSONObject(connection.inputStream.bufferedReader().use { it.readText() }) else null
                } finally { connection.disconnect() }
            }
            assertEquals(401, post("tools/list", JSONObject(), "Bearer wrong").first)
            val init = post("initialize", JSONObject().put("protocolVersion", "2025-06-18")).second!!.getJSONObject("result")
            assertEquals("2025-06-18", init.getString("protocolVersion"))
            val list = post("tools/list", JSONObject()).second!!.getJSONObject("result").getJSONArray("tools")
            assertEquals(1, list.length())
            assertEquals("open_in_codex", list.getJSONObject(0).getString("name"))
            val call = post("tools/call", JSONObject().put("name", "open_in_codex")
                .put("arguments", JSONObject("""{"target":{"type":"file","path":"demo.txt","line":2}}"""))
                .put("_meta", JSONObject().put("threadId", "t1"))).second!!.getJSONObject("result")
            assertFalse(call.getBoolean("isError"))
            assertEquals("queued", call.getJSONObject("structuredContent").getString("status"))
            assertEquals("t1", call.getJSONObject("structuredContent").getString("threadId"))
            assertEquals(1, calls.get())
            assertFalse(call.toString().contains("demo.txt"))
        } finally { host.stop() }
    }

    @Test fun previewIsRestrictedToCallingWorkspaceAndBoundedFile() {
        val root = Files.createTempDirectory("preview-test").toFile()
        try {
            val workspace = File(root, "project").apply { mkdirs() }
            val inside = File(workspace, "demo.txt").apply { writeText("first\nsecond") }
            val outside = File(root, "other.txt").apply { writeText("private") }
            assertEquals(inside.canonicalFile, ModelPreviewPolicy.resolve(root, workspace.absolutePath, "demo.txt"))
            assertThrows(IllegalArgumentException::class.java) { ModelPreviewPolicy.resolve(root, workspace.absolutePath, "../other.txt") }
            assertThrows(IllegalArgumentException::class.java) { ModelPreviewPolicy.resolve(root, workspace.absolutePath, outside.absolutePath) }
            val link = File(workspace, "link")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { ModelPreviewPolicy.resolve(root, workspace.absolutePath, "link") }
            val large = File(workspace, "large")
            java.io.RandomAccessFile(large, "rw").use { it.setLength(ModelPreviewPolicy.MAX_BYTES.toLong() + 1) }
            assertThrows(IllegalArgumentException::class.java) { ModelPreviewPolicy.resolve(root, workspace.absolutePath, "large") }
            val request = ModelPreviewRequest("p", "t", 1, 1, workspace.absolutePath, "demo.txt", 2)
            assertEquals("first\nsecond", ModelPreviewPolicy.read(root, request).toString(Charsets.UTF_8))
            assertThrows(IllegalArgumentException::class.java) {
                ModelPreviewPolicy.target(JSONObject("""{"target":{"type":"file","path":"demo.txt","line":0}}"""))
            }
            assertThrows(IllegalArgumentException::class.java) {
                ModelPreviewPolicy.thread(JSONObject("""{"threadId":"a","x-codex-turn-metadata":{"thread_id":"b"}}"""))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun sourceLineStartsAtVisibleChunkAndUnknownImageMetadataIsPreserved() {
        val snapshot = DocumentSourceSnapshot.create("first\nsecond\nthird", 2)
        assertEquals("second\nthird", snapshot.chunks[snapshot.focus!!])
        assertEquals(6, snapshot.highlightLength)
        assertNull(DocumentSourceSnapshot.create("short", 99).focus)
        val raw = JSONObject("""{"id":"image","type":"imageGeneration","status":"completed","transparentBackground":true,"imageMetadata":{"futureKey":{"nested":[false,0,null]}}}""")
        val item = TimelineEventReducer.project(raw, "turn", "completed")
        assertEquals(raw.getJSONObject("imageMetadata").toString(), JSONObject(item.evidence).getJSONObject("imageMetadata").toString())
        assertEquals("透明背景 · 生成信息 1 项 · 执行详情可查", ImageGenerationPresentation.summary(raw))
        assertNull(ImageGenerationPresentation.summary(JSONObject()))
    }
}
