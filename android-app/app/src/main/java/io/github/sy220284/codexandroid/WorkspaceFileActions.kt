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

    fun nameError(name: String): String? {
        val normalized = name.trim()
        return when {
            normalized.isEmpty() || normalized == "." || normalized == ".." ||
                normalized.contains('/') || normalized.contains('\\') ||
                normalized.contains('\u0000') -> "文件名只能包含合法的单层名称"
            normalized.startsWith(".codex-import-") -> "该前缀为工作区导入缓存保留名称"
            else -> null
        }
    }

    fun child(root: File, folder: File, name: String): File {
        val normalized = name.trim()
        nameError(normalized)?.let { throw IllegalArgumentException(it) }
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

    /** 复制使用同目录暂存，完成后一次性改名；不跟随符号链接或覆盖既有目标。 */
    fun copy(root: File, target: File, destinationFolder: File, newName: String): File {
        val validated = source(root, target).canonicalFile
        require(!Files.isSymbolicLink(target.toPath())) { "不能复制符号链接" }
        require(validated.isFile || validated.isDirectory) { "只支持复制普通文件或目录" }
        val destination = child(root, destinationFolder, newName)
        require(!destination.exists()) { "目标名称已经存在" }
        if (validated.isDirectory) require(!destination.toPath().startsWith(validated.toPath())) {
            "目录不能复制到自身内部"
        }
        val parent = destination.parentFile.toPath()
        val stage = if (validated.isDirectory)
            Files.createTempDirectory(parent, ".codex-copy-")
        else Files.createTempFile(parent, ".codex-copy-", ".part")
        try {
            if (validated.isDirectory) {
                val sourceRoot = validated.toPath()
                Files.walkFileTree(sourceRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(!attrs.isSymbolicLink) { "来源包含符号链接目录" }
                        if (dir != sourceRoot)
                            Files.createDirectory(stage.resolve(sourceRoot.relativize(dir)))
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        require(attrs.isRegularFile && !attrs.isSymbolicLink) {
                            "来源包含特殊文件或符号链接"
                        }
                        Files.copy(file, stage.resolve(sourceRoot.relativize(file)),
                            StandardCopyOption.COPY_ATTRIBUTES)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                Files.copy(validated.toPath(), stage, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES)
            }
            Files.move(stage, destination.toPath())
            return destination
        } catch (failure: Exception) {
            if (Files.exists(stage)) {
                try {
                    delete(root, stage.toFile())
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    private const val EDITABLE_BYTES = 1_000_000

    /** Strict UTF-8 decoding avoids accidentally treating binary files as editable text. */
    fun readEditableText(root: File, file: File): String {
        val validated = source(root, file)
        require(validated.isFile) { "目标不是可编辑文件" }
        require(validated.length() <= EDITABLE_BYTES) { "文件超过 1 MB，请通过专用工具处理" }
        val bytes = validated.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= EDITABLE_BYTES) { "文件超过 1 MB，请通过专用工具处理" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val content = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("文件不是有效的 UTF-8 文本，请通过专用工具打开")
        }
        require(!content.contains('\u0000')) { "文件包含二进制数据，请通过专用工具打开" }
        return content
    }

    /** Keep the user's draft and refuse to overwrite an externally changed file. */
    fun saveEditableText(root: File, file: File, expected: String, draft: String) {
        require(draft.toByteArray(Charsets.UTF_8).size <= EDITABLE_BYTES) { "编辑内容超过 1 MB" }
        require(!draft.contains('\u0000')) { "编辑内容包含无效字符" }
        require(readEditableText(root, file) == expected) {
            "文件已被其他操作修改，草稿仍保留。请复制草稿并重新打开文件后合并修改"
        }
        saveText(root, file, draft)
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
