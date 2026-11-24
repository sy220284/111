package io.github.sy220284.codexandroid

import java.io.File

/** Drafts and images handed to Codex have independent references. */
internal object AttachmentRetentionPolicy {
    const val ORPHAN_TTL_MILLIS = 24L * 60 * 60 * 1000

    fun canRelease(
        path: String, draftItems: Collection<ComposerDraft>, activeImages: Collection<String>
    ): Boolean = path !in activeImages && draftItems.none { path in it.images }

    private fun historyMarker(root: File, image: File) = File(File(root, "history"), image.name)

    fun preserveForHistory(root: File, paths: List<String>) {
        val directory = root.canonicalFile
        paths.forEach { path ->
            val image = File(path).canonicalFile
            require(image.parentFile == directory && image.isFile) { "图片附件已失效，请重新添加" }
            val marker = historyMarker(directory, image)
            check(marker.parentFile.isDirectory || marker.parentFile.mkdirs()) { "无法保存历史图片记录" }
            marker.createNewFile()
        }
    }

    fun isHistorical(root: File, image: File): Boolean = historyMarker(root, image).isFile

    fun prune(root: File, referenced: Set<String>,
              nowMillis: Long = System.currentTimeMillis()): Int {
        if (!root.exists()) return 0
        require(root.isDirectory) { "私有附件路径不是文件夹" }
        val dir = root.canonicalFile
        val protected = referenced.map { File(it).canonicalPath }.toSet()
        val entries = root.listFiles() ?: throw IllegalStateException("无法读取私有附件目录")
        var deleted = 0
        for (entry in entries) {
            val file = entry.canonicalFile
            if (!file.isFile || file.parentFile != dir || file.path in protected || isHistorical(dir, file) ||
                file.lastModified() >= nowMillis - ORPHAN_TTL_MILLIS) continue
            check(file.delete()) { "无法清理无引用的旧图片：" + file.name }
            deleted++
        }
        return deleted
    }
}
