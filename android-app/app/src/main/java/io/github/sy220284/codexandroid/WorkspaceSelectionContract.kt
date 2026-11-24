package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A thread cwd is a real directory inside the authorized shared workspace.
 * Canonical paths reject traversal and symlinks escaping the workspace root.
 */
object WorkspaceSelectionContract {
    data class Restored(val directory: File, val warning: String? = null)

    fun validate(root: File, folder: File): File {
        val base = root.canonicalFile
        val selected = folder.canonicalFile
        require(selected == base || selected.path.startsWith(base.path + File.separator)) {
            "只能选择当前应用工作区内的项目目录"
        }
        require(selected.isDirectory) { "选定的项目目录已移动或删除，请重新选择" }
        return selected
    }

    /** Old thread and draft records keep their original identity but use the migrated path. */
    fun remap(legacy: File, root: File, candidate: File): File {
        val source = legacy.canonicalFile
        val file = candidate.canonicalFile
        if (file == source) return root.canonicalFile
        if (!file.path.startsWith(source.path + File.separator)) return file
        val mapped = File(root, file.relativeTo(source).path).canonicalFile
        require(mapped.path.startsWith(root.canonicalPath + File.separator)) { "迁移路径越界" }
        return mapped
    }

    private fun digest(file: File): ByteArray {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }

    /** Copy without replacing existing phone files. Identical entries make interrupted migration replayable. */
    fun migrate(legacy: File, root: File) {
        require(root.isDirectory) { "共享工作区不存在" }
        if (!legacy.exists()) return
        require(legacy.isDirectory && legacy.canonicalFile != root.canonicalFile) { "旧工作区无效" }
        fun copy(source: File, target: File) {
            require(!Files.isSymbolicLink(source.toPath())) { "旧项目包含符号链接，请整理后重试：${source.name}" }
            require(target.canonicalFile == target.absoluteFile) { "迁移目标路径异常：${target.name}" }
            if (source.isDirectory) {
                require(target.isDirectory || (!target.exists() && target.mkdir())) {
                    "手机工作区已有同名文件：${target.name}"
                }
                val children = requireNotNull(source.listFiles()) { "无法读取旧项目：${source.name}" }
                children.forEach { copy(it, File(target, it.name)) }
            } else {
                require(source.isFile) { "旧项目包含特殊文件：${source.name}" }
                if (target.exists()) {
                    require(target.isFile && target.length() == source.length() &&
                        digest(source).contentEquals(digest(target))) { "手机工作区已有不同内容的同名文件，请先重命名：${target.name}" }
                } else {
                    val stage = Files.createTempFile(target.parentFile.toPath(), ".codex-import-", ".part")
                    try {
                        Files.copy(source.toPath(), stage, StandardCopyOption.REPLACE_EXISTING)
                        check(digest(source).contentEquals(digest(stage.toFile()))) { "旧文件迁移校验失败：${source.name}" }
                        Files.move(stage, target.toPath())
                    } finally { Files.deleteIfExists(stage) }
                }
            }
        }
        requireNotNull(legacy.listFiles()) { "无法读取旧工作区" }.forEach {
            copy(it, File(root.canonicalFile, it.name))
        }
    }

    fun restore(root: File, stored: String?): Restored {
        val base = validate(root, root)
        if (stored.isNullOrBlank()) return Restored(base)
        return try {
            Restored(validate(base, File(stored)))
        } catch (error: Exception) {
            Restored(base, "之前选择的任务项目已移动或删除，已恢复到本机工作区根目录，请重新选择")
        }
    }
}
