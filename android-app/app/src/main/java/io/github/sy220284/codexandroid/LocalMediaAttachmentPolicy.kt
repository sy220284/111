package io.github.sy220284.codexandroid

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Video manifests bind source and all derived inputs to one draft and history ownership unit. */
internal object LocalMediaAttachmentPolicy {
    fun kind(path: String): String = when (File(path).extension.lowercase(java.util.Locale.ROOT)) {
        "jpg", "jpeg", "png", "webp" -> "image"
        "mp4" -> "video"
        "wav", "mp3", "m4a", "ogg", "webm" -> "audio"
        else -> error("不支持的媒体附件类型")
    }
    fun hasAudio(path: String): Boolean {
        if (kind(path) != "video") return kind(path) == "audio"
        val metadata = File(path + ".metadata.json")
        return if (metadata.isFile && metadata.length() in 1..4096) JSONObject(metadata.readText()).getBoolean("audio")
            else File(path).name.endsWith(".av.mp4")
    }
    suspend fun prepare(paths: List<String>, root: File, checkActive: () -> Unit = {}) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val context = currentCoroutineContext()
            val folder = root.canonicalFile
            for (path in paths) if (kind(path) == "video") {
                require(File(path).canonicalFile.parentFile == folder && File(path).isFile) { "视频不属于本机私有附件目录" }
                context.ensureActive(); checkActive()
                VideoAttachmentPreparation.prepare(File(path)) { context.ensureActive(); checkActive() }
            }
        }
    }

    fun frames(path: String): String = VideoTimeline.sheet(path, 0)
    fun previews(path: String): List<String> = if (kind(path) == "video")
        VideoTimeline.read(path).segments.indices.map { VideoTimeline.sheet(path, it) } else listOf(path)

    fun ownedPaths(path: String): List<String> {
        if (kind(path) != "video") return listOf(path)
        val metadata = File(path + ".metadata.json").takeIf { it.isFile }?.path
        if (!File(VideoTimeline.manifest(path)).isFile) return listOfNotNull(path, metadata)
        val timeline = VideoTimeline.read(path)
        return listOfNotNull(path, metadata, VideoTimeline.manifest(path)) + timeline.segments.flatMapIndexed { i, segment ->
            listOf(VideoTimeline.sheet(path, i)) + (if (segment.audioStartUs != null) listOf(VideoTimeline.audio(path, i)) else emptyList())
        }
    }

    /** Used by recovery/cleanup only, so an invalid manifest cannot strand a draft or delete others. */
    fun recoveryPaths(path: String): List<String> {
        if (kind(path) != "video") return listOf(path)
        return listOf(path, path + ".metadata.json", VideoTimeline.manifest(path)) + (0 until 10).flatMap {
            listOf(VideoTimeline.sheet(path, it), VideoTimeline.audio(path, it))
        }
    }

    fun modalities(paths: List<String>): Set<String> = paths.flatMap { path ->
        when (kind(path)) {
            "video" -> listOf("image") + (if (hasAudio(path)) listOf("audio") else emptyList())
            else -> listOf(kind(path))
        }
    }.toSet()

    fun label(path: String): String = when (kind(path)) {
        "video" -> if (!File(VideoTimeline.manifest(path)).isFile) "视频 · 原文件" else
            if (hasAudio(path)) "视频 · 智能抽帧+音轨" else "视频 · 智能抽帧"
        "audio" -> "音频"
        else -> "图片"
    }

    fun append(input: JSONArray, path: String) {
        when (kind(path)) {
            "image" -> input.put(JSONObject().put("type", "localImage").put("path", path))
            "audio" -> input.put(JSONObject().put("type", "localAudio").put("path", path))
            "video" -> {
                val timeline = VideoTimeline.read(path)
                require(ownedPaths(path).all { File(it).isFile && File(it).length() > 0 }) { "视频或解析结果已失效，请重新导入" }
                require(hasAudio(path) == timeline.segments.any { it.audioStartUs != null }) { "视频音轨标记与时间轴不一致" }
                val total = timeline.segments.sumOf { it.times.size }
                input.put(JSONObject().put("type", "text").put("text",
                    "视频总时长 ${timeline.durationMs}ms，分为 ${timeline.segments.size} 段，共 ${total} 个自适应采样画面。" +
                    "每段按时间顺序提供画面与对应音轨；标注 ~ 表示请求采样时间，实际解码帧可能有偏差。" +
                    "请按时间轴逐段分析，再汇总全局。需要时转录音轨并注明时间范围；区分可见、可听和推测内容，不可断言未采样画面。"))
                timeline.segments.forEachIndexed { i, segment ->
                    val audioDescription = segment.audioStartUs?.let { "音轨首样本位于原始视频 ${it / 1000.0}ms；音频内部时间须加该偏移。" }
                        ?: "此段无音频样本。"
                    input.put(JSONObject().put("type", "text").put("text",
                        "第 ${i + 1} 段：${segment.startMs}–${segment.endMs}ms；采样时间(ms)：${segment.times.joinToString("、")}。$audioDescription"))
                    input.put(JSONObject().put("type", "localImage").put("path", VideoTimeline.sheet(path, i)))
                    if (segment.audioStartUs != null) input.put(JSONObject().put("type", "localAudio").put("path", VideoTimeline.audio(path, i)))
                }
            }
        }
    }
}
