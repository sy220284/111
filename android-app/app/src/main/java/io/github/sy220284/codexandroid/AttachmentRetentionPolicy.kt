package io.github.sy220284.codexandroid

import java.io.File

/** Protect referenced drafts forever; only old, unreferenced local files expire. */
internal object AttachmentRetentionPolicy {
    const val ORPHAN_TTL_MILLIS = 24L * 60 * 60 * 1000

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
            if (!file.isFile || file.parentFile != dir || file.path in protected ||
                file.lastModified() >= nowMillis - ORPHAN_TTL_MILLIS) continue
            check(file.delete()) { "无法清理无引用的旧图片：" + file.name }
            deleted++
        }
        return deleted
    }
}
