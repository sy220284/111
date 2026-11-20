package io.github.sy220284.codexandroid

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream

/**
 * Transactional workspace import. An incomplete upload or invalid ZIP never
 * overwrites existing project files or leaves a half-extracted target folder.
 */
object WorkspaceImport {
    private const val MAX_BYTES = 120L * 1024 * 1024
    private const val MAX_ENTRIES = 12000
    private const val STAGING_PREFIX = ".codex-import-"

    fun importFile(root: File, folder: File, name: String, input: InputStream): String {
        val target = WorkspaceFileActions.child(root, folder, name)
        require(!target.exists()) { "已存在同名文件，请先重命名" }
        val staged = Files.createTempFile(folder.canonicalFile.toPath(), STAGING_PREFIX, ".part")
        try {
            Files.newOutputStream(staged, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                val buffer = ByteArray(8192)
                var bytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_BYTES) { "导入文件超过 120 MB 限额" }
                    output.write(buffer, 0, count)
                }
            }
            Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return "已导入文件：" + target.name
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    fun importZip(root: File, folder: File, name: String, input: InputStream): String {
        val folderName = name.substringBeforeLast('.').ifBlank { "导入工程" }
        val target = WorkspaceFileActions.child(root, folder, folderName)
        require(!target.exists()) { "已存在同名工程目录，请先重命名" }
        val staged = Files.createTempDirectory(folder.canonicalFile.toPath(), STAGING_PREFIX).toFile()
        val stagedRoot = staged.canonicalFile
        try {
            var count = 0
            var bytes = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "ZIP 条目超过 12,000 项上限" }
                    require(!entry.name.startsWith('/') && !entry.name.contains('\\')) {
                        "压缩包包含不安全路径"
                    }
                    val targetFile = File(stagedRoot, entry.name).canonicalFile
                    require(targetFile.path.startsWith(stagedRoot.path + File.separator)) {
                        "压缩包包含越界路径"
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(targetFile.toPath())
                    } else {
                        Files.createDirectories(targetFile.parentFile.toPath())
                        Files.newOutputStream(targetFile.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                bytes += read
                                require(bytes <= MAX_BYTES) { "ZIP 解压内容超过 120 MB 上限" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(count > 0) { "ZIP 压缩包没有可导入条目" }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return "已导入工程：" + target.name + "（" + count + " 项）"
        } catch (failure: Exception) {
            try {
                if (staged.exists()) WorkspaceFileActions.delete(root, staged)
            } catch (cleanup: Exception) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** A crash can leave a temporary import; remove old staging artifacts on entry. */
    fun cleanAbandoned(root: File, folder: File) {
        val dir = folder.canonicalFile
        val before = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        val children = dir.listFiles() ?: throw IllegalStateException("无法读取导入缓存目录")
        children.filter { it.name.startsWith(STAGING_PREFIX) && it.lastModified() < before }
            .forEach { WorkspaceFileActions.delete(root, it) }
    }

    fun isStagingName(name: String): Boolean = name.startsWith(STAGING_PREFIX)
}
