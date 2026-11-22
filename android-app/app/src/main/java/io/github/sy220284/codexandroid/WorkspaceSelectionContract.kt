package io.github.sy220284.codexandroid

import java.io.File

/**
 * A thread cwd is a real directory inside this app's private project storage.
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
