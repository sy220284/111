package io.github.sy220284.codexandroid

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** All sidecars have deterministic names; the manifest stores metadata only, never trusted paths. */
internal data class VideoTimeline(val durationMs: Long, val segments: List<Segment>) {
    data class Segment(val startMs: Long, val endMs: Long, val times: List<Long>, val audioStartUs: Long?)
    init {
        val windows = VideoSamplingPlan.windows(durationMs)
        require(segments.size == windows.size) { "视频时间轴分段不完整" }
        segments.zip(windows).forEach { (segment, window) ->
            require(segment.startMs == window.startMs && segment.endMs == window.endMs &&
                segment.times.isNotEmpty() && segment.times.size <= VideoSamplingPlan.MAX_FRAMES_PER_SEGMENT &&
                segment.times == segment.times.distinct().sorted() &&
                segment.times.all { it in window.startMs until window.endMs } &&
                (segment.audioStartUs == null || segment.audioStartUs in window.startMs * 1000 until window.endMs * 1000)) {
                "视频时间轴无效"
            }
        }
    }

    fun encode(): String = JSONObject().put("version", 2).put("durationMs", durationMs)
        .put("segments", JSONArray().apply {
            segments.forEach { segment ->
                put(JSONObject().put("startMs", segment.startMs).put("endMs", segment.endMs)
                    .put("times", JSONArray(segment.times))
                    .put("audioStartUs", segment.audioStartUs ?: JSONObject.NULL))
            }
        }).toString()

    companion object {
        fun manifest(path: String) = "$path.timeline.json"
        fun sheet(path: String, segment: Int) = "$path.segment-$segment.jpg"
        fun audio(path: String, segment: Int) = "$path.segment-$segment.m4a"

        fun read(path: String): VideoTimeline {
            val file = File(manifest(path))
            require(file.isFile && file.length() in 1..65_536L) { "视频时间轴缺失，请重新导入原视频" }
            val value = JSONObject(file.readText())
            require(value.getInt("version") == 2) { "视频时间轴版本无效，请重新导入" }
            val rows = value.getJSONArray("segments")
            require(rows.length() in 1..10) { "视频分段数量无效" }
            return VideoTimeline(value.getLong("durationMs"), (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val times = row.getJSONArray("times")
                require(times.length() in 1..VideoSamplingPlan.MAX_FRAMES_PER_SEGMENT)
                Segment(row.getLong("startMs"), row.getLong("endMs"), (0 until times.length()).map(times::getLong),
                    if (row.isNull("audioStartUs")) null else row.getLong("audioStartUs"))
            })
        }
    }
}
