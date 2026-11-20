package io.github.sy220284.codexandroid

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * All workspace edits stay under the app-owned projects root.
 * File operations are independent of Compose and can be tested on the JVM.
 */
object WorkspaceFileActions {
    private fun source(root: File, candidate: File): File {
        val base = root.canonicalFile
        val file = candidate.canonicalFile
        require(file.path.startsWith(base.path + File.separator)) {
            "目标不在当前工作区内"
        }
        require(file.exists()) { "目标已不存在" }
        return candidate
    }

    fun child(root: File, folder: File, name: String): File {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized != "." && normalized != ".." &&
            !normalized.contains('/') && !normalized.contains('\\') &&
            !normalized.contains('\u0000')) { "文件名只能包含合法的单层名称" }
        val base = root.canonicalFile
        val parent = folder.canonicalFile
        require(parent == base || parent.path.startsWith(base.path + File.separator)) {
            "父目录不属于工作区"
        }
        require(parent.isDirectory) { "当前目录已失效" }
        val target = File(parent, normalized).canonicalFile
        require(target.parentFile == parent) { "路径超出工作区" }
        return target
    }

    fun createFolder(root: File, folder: File, name: String): File {
        val target = child(root, folder, name)
        Files.createDirectory(target.toPath())
        return target
    }

    fun createTextFile(root: File, folder: File, name: String): File {
        val target = child(root, folder, name)
        Files.createFile(target.toPath())
        return target
    }

    fun rename(root: File, target: File, name: String): File {
        val validated = source(root, target)
        val replacement = child(root, validated.parentFile, name)
        require(!replacement.exists()) { "同名文件或文件夹已经存在" }
        Files.move(validated.toPath(), replacement.toPath())
        return replacement
    }

    /** Do not follow symbolic links while deleting a directory tree. */
    fun delete(root: File, target: File) {
        val validated = source(root, target)
        Files.walkFileTree(validated.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** A save either replaces the full file or keeps the original byte-for-byte. */
    fun saveText(root: File, file: File, text: String) {
        val validated = source(root, file)
        require(validated.isFile) { "目标不是可编辑文件" }
        val temporary = Files.createTempFile(validated.parentFile.toPath(), ".codex-edit-", ".part")
        try {
            Files.write(temporary, text.toByteArray(Charsets.UTF_8))
            Files.move(temporary, validated.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
