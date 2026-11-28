package io.github.sy220284.codexandroid

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.DigestInputStream
import java.util.zip.ZipInputStream

/** Exact signed payload content; no symlink traversal or unlisted files. */
internal object RuntimePayloadIntegrity {
    data class Entry(val name: String, val digest: String, val size: Long, val canonical: String)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun manifestDigest(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun manifest(bytes: ByteArray): List<Entry> {
        val names = mutableSetOf<String>()
        val canonical = mutableMapOf<String, Pair<String, Long>>()
        val lines = bytes.toString(Charsets.UTF_8).removeSuffix("\n").split("\n")
        val entries = lines.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "开发环境文件清单格式异常" }
            val (name, digest, rawSize) = fields
            require(name.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                !name.contains('\\') && !name.contains('\r') && !name.contains('\u0000') &&
                names.add(name) && digest.matches(Regex("[a-f0-9]{64}"))) { "开发环境文件清单路径异常" }
            val size = rawSize.toLong()
            require(size >= 0 && size <= 512L * 1024 * 1024)
            val first = canonical.getOrPut(digest) { name to size }
            require(first.second == size) { "开发环境文件摘要与大小不一致" }
            Entry(name, digest, size, first.first)
        }
        require(entries.isNotEmpty() && entries.sumOf { it.size } <= 512L * 1024 * 1024) {
            "开发环境数据超过安全上限"
        }
        require(entries.map { it.name } == entries.map { it.name }.sorted()) { "开发环境清单顺序异常" }
        return entries
    }

    private fun sha(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) {
            val n = input.read(bytes)
            if (n < 0) break
            digest.update(bytes, 0, n)
        }
        return hex(digest.digest())
    }

    /** Keep the full signed-content check without inflating the APK archive again. */
    fun matches(root: File, entries: List<Entry>): Boolean {
        val base = root.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(base, NOFOLLOW_LINKS) || Files.isSymbolicLink(base.parent)) return false
        val expected = entries.associateBy { it.name }
        val directories = mutableSetOf(base)
        for (entry in entries) {
            val path = base.resolve(entry.name).normalize()
            if (!path.startsWith(base) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
            var parent = path.parent
            while (parent != base) {
                if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return false
                directories.add(parent)
                parent = parent.parent
            }
            if (Files.size(path) != entry.size) return false
            if (entry.canonical == entry.name) {
                if (Files.newInputStream(path, NOFOLLOW_LINKS).use(::sha) != entry.digest) return false
            } else {
                val canonical = base.resolve(entry.canonical)
                if (!Files.isRegularFile(canonical, NOFOLLOW_LINKS) || !Files.isSameFile(path, canonical)) return false
            }
        }
        return entries.isNotEmpty() && Files.walk(base).use { paths ->
            paths.allMatch { path ->
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) path in directories
                else Files.isRegularFile(path, NOFOLLOW_LINKS) &&
                    base.relativize(path).toString().replace(File.separatorChar, '/') in expected
            }
        }
    }

    /** Extract each unique file directly from the signed APK; no temporary ZIP copy. */
    fun install(root: File, source: InputStream, entries: List<Entry>, archiveDigest: String) {
        val base = root.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(base, NOFOLLOW_LINKS) && root.list()?.isEmpty() == true)
        val expected = entries.filter { it.name == it.canonical }.associateBy { it.name }
        val seen = mutableSetOf<String>()
        val archiveHash = MessageDigest.getInstance("SHA-256")
        DigestInputStream(source, archiveHash).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                val buffer = ByteArray(65536)
                while (true) {
                    val item = zip.nextEntry ?: break
                    val entry = requireNotNull(expected[item.name]) { "开发环境压缩包存在未知文件" }
                    require(!item.isDirectory && seen.add(item.name))
                    val path = base.resolve(entry.name).normalize()
                    require(path.startsWith(base))
                    Files.createDirectories(path.parent)
                    val contentHash = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            size += n
                            require(size <= entry.size) { "开发环境文件超过清单大小" }
                            contentHash.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                    require(size == entry.size && hex(contentHash.digest()) == entry.digest) { "开发环境文件校验失败" }
                    zip.closeEntry()
                }
                // ZipInputStream stops at the central directory. Read the remaining
                // raw bytes as well; buffered read-ahead was already hashed.
                while (raw.read(buffer) >= 0) { }
            }
        }
        require(seen == expected.keys && hex(archiveHash.digest()) == archiveDigest) { "开发环境归档校验失败" }
        for (entry in entries.filter { it.name != it.canonical }) {
            val path = base.resolve(entry.name)
            Files.createDirectories(path.parent)
            Files.createLink(path, base.resolve(entry.canonical))
        }
        check(matches(root, entries)) { "开发环境数据不完整" }
    }

    /** Only versioned product payloads are owned here, never user packages.
     * A new APK is started after Android replaces the old package processes;
     * within one installed APK the signed manifest identity cannot change. */
    fun removeRetired(parent: File, active: File) {
        require(Files.isDirectory(active.toPath(), NOFOLLOW_LINKS) && active.parentFile == parent)
        val owned = Regex("[a-f0-9]{64}(\\.partial)?")
        requireNotNull(parent.listFiles()).filter { it != active && owned.matches(it.name) }.forEach(::removeTree)
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
