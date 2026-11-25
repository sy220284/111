package io.github.sy220284.codexandroid

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

    fun removeTree(root: File) {
        val path = root.toPath()
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}
