package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Only documented Model Studio Chat Completions formats; never inject an unsupported Codex input_video. */
internal object QwenMediaWire {
    const val OMNI = "qwen3.8-omni-flash"
    const val ASR = "qwen3-asr-flash"
    // Data URLs must remain under the provider's 10 MB encoded limit.
    const val MAX_SOURCE_BYTES = 7_000_000L
    const val MAX_RESULT_CHARS = 24_000

    enum class Mode(val label: String, val model: String) {
        NativeVideo("原视频分析", OMNI),
        SegmentedVideo("逐段深度分析", OMNI),
        Transcription("语音转录", ASR)
    }

    fun accepts(mode: Mode, kind: String, size: Long, audio: Boolean): Boolean = when (mode) {
        Mode.NativeVideo -> kind == "video" && size in 1..MAX_SOURCE_BYTES
        Mode.SegmentedVideo -> kind == "video"
        Mode.Transcription -> kind == "audio" || (kind == "video" && audio)
    }

    fun dataUri(mime: String, bytes: ByteArray): String {
        require(bytes.size.toLong() in 1..MAX_SOURCE_BYTES) { "单个媒体超过原生接口大小上限" }
        require(mime in setOf("video/mp4", "image/jpeg", "audio/wav", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/webm"))
        return "data:$mime;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
    }

    fun videoPart(uri: String): JSONObject {
        require(uri.startsWith("data:video/mp4;base64,"))
        // Model Studio's documented local video example uses an untyped data URL.
        return JSONObject().put("type", "video_url")
            .put("video_url", JSONObject().put("url", "data:;base64," + uri.substringAfter(',')))
    }
    fun imagePart(uri: String) = JSONObject().put("type", "image_url")
        .put("image_url", JSONObject().put("url", uri))
    fun audioPart(uri: String, format: String? = null) = JSONObject().put("type", "input_audio")
        .put("input_audio", JSONObject().put("data", uri).apply { if (format != null) put("format", format) })

    private fun message(content: JSONArray) = JSONArray().put(JSONObject().put("role", "user").put("content", content))
    fun omni(parts: List<JSONObject>, prompt: String): JSONObject {
        require(parts.isNotEmpty() && prompt.isNotBlank())
        val content = JSONArray()
        parts.forEach { content.put(it) }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        return JSONObject().put("model", OMNI).put("messages", message(content))
            .put("modalities", JSONArray().put("text")).put("stream", true).put("max_tokens", 1600)
    }
    fun asr(uri: String) = JSONObject().put("model", ASR)
        .put("messages", message(JSONArray().put(audioPart(uri))))
        .put("stream", false).put("asr_options", JSONObject().put("enable_itn", false))

    fun transcript(response: JSONObject): String {
        require(response.optString("model") == ASR || response.optString("model").startsWith("$ASR-")) { "转录模型回执不匹配" }
        val choice = response.getJSONArray("choices").getJSONObject(0)
        require(choice.optString("finish_reason") == "stop") { "语音转录未完整结束" }
        val result = choice.getJSONObject("message").getString("content")
        require(result.length <= MAX_RESULT_CHARS) { "转录结果超出本地上限" }
        return result
    }

    /** Accept only a completed stream; partial text is never published as a finished result. */
    fun parseOmni(lines: Sequence<String>, checkActive: () -> Unit = {}): String {
        val output = StringBuilder()
        var finished = false
        var done = false
        for (line in lines) {
            checkActive()
            if (!line.startsWith("data:")) continue
            val value = line.removePrefix("data:").trim()
            if (value == "[DONE]") { done = true; break }
            val event = JSONObject(value)
            require(!event.has("error")) { "视频分析服务返回错误" }
            val model = event.optString("model")
            require(model.isEmpty() || model == OMNI || model.startsWith("$OMNI-")) { "视频分析模型回执不匹配" }
            val choices = event.optJSONArray("choices") ?: continue
            if (choices.length() == 0) continue
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")?.optString("content").orEmpty()
            if (delta != "null") output.append(delta)
            require(output.length <= MAX_RESULT_CHARS) { "视频分析结果超出本地上限" }
            val finish = choice.optString("finish_reason")
            if (finish.isNotBlank() && finish != "null") {
                require(finish == "stop") { "视频分析提前结束，请缩小问题范围后重试" }
                finished = true
            }
        }
        require(done && finished && output.isNotBlank()) { "视频分析响应不完整，原附件已保留" }
        return output.toString()
    }
}
