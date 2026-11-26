package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Validate portable media as a graph, not just a list of filenames. No imported path is opened. */
internal object PortableMediaArchive {
    private fun entries(folder: File, accepts: (String) -> Boolean): List<File> {
        if (!folder.exists()) return emptyList()
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) { "媒体数据目录无效" }
        return requireNotNull(folder.listFiles()) { "无法读取媒体数据" }.filter { accepts(it.name) }.onEach {
            require(it.isFile && !Files.isSymbolicLink(it.toPath())) { "媒体数据包含无效文件或符号链接" }
        }
    }

    private fun record(file: File): AnalysisCheckpoint {
        require(file.length() in 1..AnalysisCheckpoint.MAX_RECORD_BYTES) { "媒体分析记录大小无效" }
        val record = AnalysisCheckpoint.decode(file.readText())
        val screen = record.source == "screen"
        require(file.name == record.storageName(screen) ||
            (!screen && file.name == AnalysisCheckpoint.storageBase(record.owner, record.scope, false) + ".json")) {
            "媒体分析记录归属与文件名不一致"
        }
        return record
    }

    private fun original(attachments: File, name: String): File {
        require(PortableMediaFormat.original(name)) { "媒体原件名称无效" }
        return File(attachments, name).also {
            require(it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() > 0) { "备份缺少媒体原件：$name" }
        }
    }

    fun verify(files: File, sourceRoot: File? = null) {
        val attachments = File(files, PortableMediaFormat.ATTACHMENTS)
        for (file in entries(attachments, PortableMediaFormat::attachment)) {
            require(file.length() > 0) { "媒体文件为空：" + file.name }
            PortableMediaFormat.sidecarSource(file.name)?.let { original(attachments, it) }
            if (file.name.endsWith("timeline.json")) {
                val timeline = VideoTimeline.readManifest(file)
                val source = requireNotNull(PortableMediaFormat.sidecarSource(file.name))
                timeline.segments.forEachIndexed { index, segment ->
                    val paths = (if (timeline.hasVisual) listOf(VideoTimeline.sheet(source, index)) else emptyList()) +
                        (if (segment.audioStartUs != null) listOf(VideoTimeline.audio(source, index)) else emptyList())
                    require(paths.all { File(attachments, it).isFile && File(attachments, it).length() > 0 }) {
                        "视频时间轴引用的画面或音轨缺失：" + file.name
                    }
                }
            } else if (file.name.endsWith(".metadata.json")) {
                require(file.length() <= 4096) { "视频元数据过大" }
                val metadata = JSONObject(file.readText())
                require(metadata.getLong("durationMs") > 0 && metadata.opt("audio") is Boolean) { "视频元数据无效" }
            }
        }
        for (file in entries(File(attachments, "history"), PortableMediaFormat::history)) {
            if (PortableMediaFormat.historyMessage(file.name)) {
                val id = file.name.removePrefix("message-").removeSuffix(".json")
                MediaHistoryStore.read(attachments, id).forEach { original(attachments, File(it).name) }
            } else {
                require(file.length() == 0L && File(attachments, file.name).isFile) { "历史媒体保留标记失去原件" }
            }
        }
        for (file in entries(File(files, PortableMediaFormat.ANALYSES), PortableMediaFormat::analysis)) {
            val record = record(file)
            if (record.source == "screen") {
                require(record.mode == "screen") { "屏幕分析记录类型无效" }
            } else {
                val source = File(record.source)
                require(source.isAbsolute && (sourceRoot == null || source.parentFile?.canonicalFile == sourceRoot.canonicalFile)) {
                    "分析原件路径不属于媒体目录"
                }
                original(attachments, source.name)
            }
        }
    }

    /** Rebase only validated managed originals; recompute the dependent record identity before staging. */
    fun relocate(files: File, destinationFiles: File) {
        verify(files)
        val folder = File(files, PortableMediaFormat.ANALYSES)
        val destination = File(destinationFiles, PortableMediaFormat.ATTACHMENTS).canonicalFile
        val records = entries(folder, PortableMediaFormat::analysis)
        if (records.isEmpty()) return
        val prepared = File(files, ".portable-media-rebase")
        check(prepared.mkdir()) { "无法建立媒体分析迁移目录" }
        val originals = mutableListOf<File>()
        for (file in records) {
            val record = record(file)
            if (record.source != "screen") {
                val updated = record.copy(source = File(destination, File(record.source).name).path)
                val target = File(prepared, updated.storageName(false))
                require(!target.exists()) { "媒体分析记录在迁移后发生冲突" }
                target.writeText(updated.encode())
                originals += file
            }
        }
        val rebased = requireNotNull(prepared.listFiles())
        require(rebased.all { !File(folder, it.name).exists() || File(folder, it.name) in originals }) {
            "迁移后的媒体分析记录已存在"
        }
        // Incoming data is disposable until staging commits. Keep memory bounded to one record.
        originals.forEach { check(it.delete()) { "无法替换暂存媒体分析记录" } }
        rebased.forEach { check(it.renameTo(File(folder, it.name))) { "无法提交暂存媒体分析记录" } }
        check(prepared.delete()) { "无法清理媒体分析迁移目录" }
        verify(files, destination)
    }
}
