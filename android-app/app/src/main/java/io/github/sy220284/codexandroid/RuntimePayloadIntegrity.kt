package io.github.sy220284.codexandroid

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Exact signed payload content; no symlink traversal or unlisted files. */
internal object RuntimePayloadIntegrity {
    private fun sha(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) {
            val n = input.read(bytes)
            if (n < 0) break
            digest.update(bytes, 0, n)
        }
        return digest.digest()
    }

    fun matches(root: File, source: InputStream): Boolean {
        val base = root.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(base, NOFOLLOW_LINKS) || Files.isSymbolicLink(base.parent)) return false
        val expected = mutableSetOf<String>()
        val directories = mutableSetOf(base)
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.trimEnd('/')
                if (name.split('/').any { it.isEmpty() || it == "." || it == ".." } || name.contains('\\')) return false
                val path = base.resolve(name).normalize()
                if (!path.startsWith(base) || !expected.add(name)) return false
                var parent = if (entry.isDirectory) path else path.parent
                while (parent != base) {
                    if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return false
                    directories.add(parent)
                    parent = parent.parent
                }
                if (!entry.isDirectory) {
                    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
                    if (!MessageDigest.isEqual(sha(zip), Files.newInputStream(path).use(::sha))) return false
                }
                zip.closeEntry()
            }
        }
        if (expected.isEmpty()) return false
        return Files.walk(base).use { paths ->
            paths.allMatch { path ->
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) path in directories
                else Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                    base.relativize(path).toString().replace(File.separatorChar, '/') in expected
            }
        }
    }

    private val writePermissions = setOf(
        PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE
    )

    /** Prevent accidental edits; the app and native core share a UID, so mode bits
     * alone are not a sandbox. The signed asset remains the integrity authority. */
    fun sealReadOnly(root: File) {
        val base = root.toPath()
        require(Files.isDirectory(base, NOFOLLOW_LINKS)) { "开发运行时目录无效" }
        Files.walk(base).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                check(Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    "开发运行时包含非普通文件"
                }
                val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                if (permissions.any { it in writePermissions }) {
                    Files.setPosixFilePermissions(path, permissions - writePermissions)
                }
                check(Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).none { it in writePermissions }) {
                    "开发运行时无法设置只读权限"
                }
            }
        }
    }

    /** Rebuild can remove a sealed tree, including a damaged directory. Never
     * traverse or change the permissions of a symbolic-link target. */
    fun removeTree(root: File) {
        fun remove(path: Path) {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                val permissions = Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
                val ownerAccess = setOf(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                if (!permissions.containsAll(ownerAccess)) {
                    Files.setPosixFilePermissions(path, permissions + ownerAccess)
                }
                Files.newDirectoryStream(path).use { children -> children.forEach { remove(it) } }
            }
            Files.delete(path)
        }
        remove(root.toPath())
    }
}
