package io.github.sy220284.codexandroid

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Draft paths remain the ownership unit; video sidecars are generated once at import. */
internal object LocalMediaAttachmentPolicy {
    fun kind(path: String): String = when (File(path).extension.lowercase(java.util.Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp" -> "image"
        "mp4" -> "video"
        "wav", "mp3", "m4a", "ogg", "webm" -> "audio"
        else -> error("不支持的媒体附件类型")
    }
    fun hasAudio(path: String): Boolean = File(path).name.endsWith(".av.mp4")
    fun frames(path: String): String = "$path.frames.jpg"
    fun sound(path: String): String = "$path.audio.m4a"
    fun ownedPaths(path: String): List<String> = if (kind(path) == "video")
        listOf(path, frames(path)) + (if (hasAudio(path)) listOf(sound(path)) else emptyList())
        else listOf(path)
    fun modalities(paths: List<String>): Set<String> = paths.flatMap { path ->
        when (kind(path)) {
            "video" -> listOf("image") + (if (hasAudio(path)) listOf("audio") else emptyList())
            else -> listOf(kind(path))
        }
    }.toSet()
    fun label(path: String): String = when (kind(path)) {
        "video" -> if (hasAudio(path)) "视频 · 抽帧+音轨" else "视频 · 抽帧"
        "audio" -> "音频"
        else -> "图片"
    }
    fun append(input: JSONArray, path: String) {
        when (kind(path)) {
            "image" -> input.put(JSONObject().put("type", "localImage").put("path", path))
            "audio" -> input.put(JSONObject().put("type", "localAudio").put("path", path))
            "video" -> {
                require(ownedPaths(path).all { File(it).isFile && File(it).length() > 0 }) { "视频或解析结果已失效，请重新导入" }
                input.put(JSONObject().put("type", "text").put("text",
                    "以下为视频的六个按时间顺序采样画面（画面标有时间戳）。" +
                        (if (hasAudio(path)) "并附完整音轨。请结合画面和音轨分析；未采样画面不可确认。" else "原视频无音轨。未采样画面不可确认。")))
                input.put(JSONObject().put("type", "localImage").put("path", frames(path)))
                if (hasAudio(path)) input.put(JSONObject().put("type", "localAudio").put("path", sound(path)))
            }
        }
    }
}
