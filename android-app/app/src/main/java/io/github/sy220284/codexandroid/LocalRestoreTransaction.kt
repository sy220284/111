package io.github.sy220284.codexandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Process-interruption-safe overlay restore. No live file changes before the
 * complete rollback journal is published. A surviving journal and pending marker
 * require rollback, never replay of an incompletely committed new snapshot. */
internal class LocalRestoreTransaction(private val journal: File) {
    private val staging = File(journal.parentFile, journal.name + ".partial")
    private data class Entry(val target: File, val existed: Boolean)
    private data class Snapshot(val token: String, val entries: List<Entry>, val createdDirs: List<File>)

    fun hasJournal(): Boolean = Files.exists(journal.toPath(), NOFOLLOW_LINKS)

    private fun remove(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun syncCopy(source: File, destination: File) {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun temporary(target: File, token: String, index: Int) =
        File(target.parentFile, ".codex-import-$token-$index.tmp")

    private fun replaceFile(source: File, target: File, token: String, index: Int) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "恢复目标目录无法创建" }
        val tmp = temporary(target, token, index)
        Files.deleteIfExists(tmp.toPath())
        Files.createFile(tmp.toPath())
        try {
            syncCopy(source, tmp)
            Files.move(tmp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(tmp.toPath()) }
    }

    fun prepare(targets: List<Pair<File, File>>) {
        check(!hasJournal()) { "存在未结束的恢复事务" }
        remove(staging)
        check(staging.mkdir())
        try {
            val token = UUID.randomUUID().toString()
            val entries = targets.map { (_, target) ->
                val existed = Files.exists(target.toPath(), NOFOLLOW_LINKS)
                require(!existed || Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS))
                Entry(target, existed)
            }
            require(entries.map { it.target.absolutePath }.toSet().size == entries.size)
            val created = linkedSetOf<File>()
            entries.forEachIndexed { index, entry ->
                require(!Files.exists(temporary(entry.target, token, index).toPath(), NOFOLLOW_LINKS))
                if (entry.existed) syncCopy(entry.target, File(staging, "$index.original"))
                var parent = entry.target.parentFile
                while (parent != null && !parent.exists()) {
                    created.add(parent)
                    parent = parent.parentFile
                }
            }
            FileOutputStream(File(staging, "manifest")).use { stream ->
                val out = DataOutputStream(stream)
                out.writeUTF(token)
                out.writeInt(entries.size)
                for (entry in entries) {
                    out.writeUTF(entry.target.absolutePath)
                    out.writeBoolean(entry.existed)
                }
                out.writeInt(created.size)
                created.forEach { out.writeUTF(it.absolutePath) }
                out.flush()
                stream.fd.sync()
            }
            Files.move(staging.toPath(), journal.toPath(), ATOMIC_MOVE)
        } finally { remove(staging) }
    }

    private fun snapshot(targets: List<Pair<File, File>>): Snapshot {
        require(Files.isDirectory(journal.toPath(), NOFOLLOW_LINKS))
        val manifest = File(journal, "manifest")
        require(Files.isRegularFile(manifest.toPath(), NOFOLLOW_LINKS))
        return DataInputStream(manifest.inputStream().buffered()).use { input ->
            val token = input.readUTF()
            require(UUID.fromString(token).toString() == token)
            require(input.readInt() == targets.size) { "恢复事务清单与暂存数据不一致" }
            val entries = targets.mapIndexed { index, (_, target) ->
                require(input.readUTF() == target.absolutePath) { "恢复事务目标已变化" }
                val existed = input.readBoolean()
                if (existed) require(Files.isRegularFile(File(journal, "$index.original").toPath(), NOFOLLOW_LINKS))
                Entry(target, existed)
            }
            val allowedParents = entries.flatMap { generateSequence(it.target.parentFile) { p -> p.parentFile }.toList() }
                .map { it.absolutePath }.toSet()
            val count = input.readInt()
            require(count in 0..allowedParents.size)
            val dirs = List(count) {
                val path = input.readUTF()
                require(path in allowedParents)
                File(path)
            }
            require(input.read() == -1)
            Snapshot(token, entries, dirs)
        }
    }

    fun apply(targets: List<Pair<File, File>>, beforeReplace: (Int) -> Unit = {}) {
        val snapshot = snapshot(targets)
        targets.forEachIndexed { index, (source, target) ->
            beforeReplace(index)
            replaceFile(source, target, snapshot.token, index)
        }
    }

    fun rollback(targets: List<Pair<File, File>>) {
        val snapshot = snapshot(targets)
        snapshot.entries.forEachIndexed { index, entry ->
            Files.deleteIfExists(temporary(entry.target, snapshot.token, index).toPath())
        }
        // Remove newly created files first so rollback has room to restore originals.
        snapshot.entries.filterNot { it.existed }.forEach { Files.deleteIfExists(it.target.toPath()) }
        snapshot.entries.forEachIndexed { index, entry ->
            if (entry.existed) replaceFile(File(journal, "$index.original"), entry.target, snapshot.token, index)
        }
        snapshot.createdDirs.sortedByDescending { it.absolutePath.length }.forEach { dir ->
            if (dir.isDirectory && dir.list()?.isEmpty() == true) Files.delete(dir.toPath())
        }
    }

    /** Only after the caller has removed the pending marker. */
    fun finish() {
        remove(journal)
        remove(staging)
    }
}
