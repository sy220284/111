package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class QwenMediaWireTest {
    @Test fun usesOriginalVideoAndDedicatedAsrWire() {
        val uri = QwenMediaWire.dataUri("video/mp4", byteArrayOf(1, 2, 3))
        val request = QwenMediaWire.omni(listOf(QwenMediaWire.videoPart(uri)), "描述视频")
        assertEquals("qwen3.8-omni-flash", request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        val part = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("video_url", part.getString("type"))
        assertEquals("data:video/mp4;base64,AQID", part.getJSONObject("video_url").getString("url"))
        val asr = QwenMediaWire.asr("data:audio/wav;base64,AQID")
        assertEquals("qwen3-asr-flash", asr.getString("model"))
        assertFalse(asr.getBoolean("stream"))
        assertEquals("input_audio", asr.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        assertFalse(QwenMediaWire.accepts(QwenMediaWire.Mode.NativeVideo, "video", 7_000_001L, true))
    }
    @Test fun completedStreamIsRequired() {
        val chunk = """data: {"model":"qwen3.8-omni-flash","choices":[{"delta":{"content":"画面"},"finish_reason":null}]}"""
        val finish = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        assertEquals("画面", QwenMediaWire.parseOmni(sequenceOf(chunk, finish, "data: [DONE]")))
        assertThrows(IllegalArgumentException::class.java) { QwenMediaWire.parseOmni(sequenceOf(chunk, "data: [DONE]")) }
        assertThrows(IllegalArgumentException::class.java) { QwenMediaWire.parseOmni(sequenceOf(chunk, finish)) }
        assertThrows(IllegalArgumentException::class.java) {
            QwenMediaWire.parseOmni(sequenceOf(chunk, finish.replace("stop", "length"), "data: [DONE]"))
        }
    }
    @Test fun transcriptionRejectsIncompleteOutput() {
        val response = JSONObject("""{"model":"qwen3-asr-flash","choices":[{"finish_reason":"stop","message":{"content":"测试"}}]}""")
        assertEquals("测试", QwenMediaWire.transcript(response))
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length")
        assertThrows(IllegalArgumentException::class.java) { QwenMediaWire.transcript(response) }
    }
}
