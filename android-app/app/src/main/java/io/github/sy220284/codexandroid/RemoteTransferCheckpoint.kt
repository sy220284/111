package io.github.sy220284.codexandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** One private checkpoint. A durable key is issued only after fresh account,
 * thread, workspace and remote source authorization. A journal alone grants no access.
 * Data is synced before atomically advancing the committed offset. */
internal class RemoteTransferCheckpoint(private val directory: File) {
    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_BYTES = 512L * 1024 * 1024
        private const val JOURNAL_VERSION = 1
        fun isDigest(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        /** Strict standard Base64, including canonical padding bits, without re-encoding the payload. */
        fun decodeChunk(encoded: String, expected: Long): ByteArray {
            require(expected in 0..CHUNK_BYTES.toLong() &&
                encoded.length.toLong() == ((expected + 2) / 3) * 4) { "远端分块大小不一致" }
            when (expected % 3) {
                1L -> require(encoded.endsWith("==") && encoded[encoded.length - 3] in "AQgw") { "无效远端分块编码" }
                2L -> require(encoded.endsWith("=") && encoded[encoded.length - 2] in "AEIMQUYcgkosw048") { "无效远端分块编码" }
            }
            return java.util.Base64.getDecoder().decode(encoded).also {
                require(it.size.toLong() == expected) { "无效远端分块编码" }
            }
        }
        fun digest(file: File, permit: () -> Unit = {}): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    permit()
                    val count = input.read(buffer)
                    if (count < 0) break
                    md.update(buffer, 0, count)
                }
            }
            return hex(md.digest())
        }
    }

    data class Source(val owner: String, val size: Long, val sha256: String, val resumeKey: String? = null) {
        init {
            require(owner.isNotBlank() && size in 0..MAX_BYTES && isDigest(sha256) &&
                (resumeKey == null || isDigest(resumeKey))) { "无效远端文件摘要或大小" }
        }
    }
    class Entry internal constructor(val source: Source, val file: File, var offset: Long = 0)
    private data class Journal(val key: String, val size: Long, val sha: String, val offset: Long)
    private val dataFile get() = File(directory, "delivery-resume.part")
    private val stateFile get() = File(directory, "delivery-resume.state")
    private val temporaryState get() = File(directory, "delivery-resume.tmp")
    private var current: Entry? = null
    private fun regular(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun delete(file: File) { Files.deleteIfExists(file.toPath()) }

    private fun readJournal(): Journal {
        require(regular(stateFile) && stateFile.length() in 1..256) { "断点清单格式无效" }
        return DataInputStream(Files.newInputStream(stateFile.toPath(), NOFOLLOW_LINKS)).use { input ->
            require(input.readInt() == JOURNAL_VERSION) { "断点清单版本不受支持" }
            val state = Journal(input.readUTF(), input.readLong(), input.readUTF(), input.readLong())
            require(isDigest(state.key) && isDigest(state.sha) && state.size in 0..MAX_BYTES &&
                state.offset in 0..state.size && (state.offset == state.size || state.offset % CHUNK_BYTES == 0L) &&
                input.read() == -1) { "断点清单内容无效" }
            state
        }
    }

    private fun commit(entry: Entry, offset: Long) {
        val key = entry.source.resumeKey ?: return
        delete(temporaryState)
        FileOutputStream(temporaryState).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(JOURNAL_VERSION)
            output.writeUTF(key)
            output.writeLong(entry.source.size)
            output.writeUTF(entry.source.sha256)
            output.writeLong(offset)
            output.flush()
            stream.fd.sync()
        }
        Files.move(temporaryState.toPath(), stateFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun begin(source: Source): Entry {
        current?.takeIf { it.source == source && regular(it.file) && it.file.length() == it.offset }?.let { return it }
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || !exists(directory) && directory.mkdirs()) {
            "无法建立私有文件暂存目录"
        }
        current?.let { if (it.file != dataFile) delete(it.file) }
        current = null
        // A durable record is only reused after the caller freshly proves the same
        // account/workspace/file and whole-source digest. Never trust an old offset alone.
        if (source.resumeKey != null && exists(stateFile)) {
            val state = try { readJournal() } catch (failure: IOException) {
                clear(); throw IOException("断点清单损坏，已清除，请重新读取", failure)
            } catch (failure: IllegalArgumentException) {
                clear(); throw IllegalStateException("断点清单损坏，已清除，请重新读取", failure)
            }
            if (state.key == source.resumeKey && state.size == source.size && state.sha == source.sha256) {
                if (!regular(dataFile) || dataFile.length() !in state.offset..state.size) {
                    clear(); error("断点文件损坏，已清除，请重新读取")
                }
                // Process death after syncing data but before publishing the journal:
                // discard the uncommitted suffix and request it again.
                if (dataFile.length() > state.offset) RandomAccessFile(dataFile, "rw").use {
                    it.setLength(state.offset); it.fd.sync()
                }
                delete(temporaryState)
                return Entry(source, dataFile, state.offset).also { current = it }
            }
        }
        clear()
        directory.listFiles()?.filter { it.name.startsWith("delivery-") && it.name.endsWith(".part") }
            ?.forEach(::delete)
        val file = if (source.resumeKey == null) File.createTempFile("delivery-", ".part", directory)
            else dataFile.also { check(it.createNewFile()) { "无法创建断点文件" } }
        val entry = Entry(source, file)
        try { commit(entry, 0) } catch (failure: Exception) {
            delete(file); delete(temporaryState); throw failure
        }
        current = entry
        return entry
    }

    fun append(entry: Entry, offset: Long, bytes: ByteArray) {
        check(current === entry && regular(entry.file) && offset == entry.offset && entry.file.length() == offset) { "断点归属或位置已变化" }
        val expected = minOf(CHUNK_BYTES.toLong(), entry.source.size - offset)
        require(offset < entry.source.size && bytes.size.toLong() == expected) { "远端分块长度不一致" }
        RandomAccessFile(entry.file, "rw").use { output ->
            try {
                output.seek(offset)
                output.write(bytes)
                output.fd.sync()
                commit(entry, offset + bytes.size)
            } catch (failure: Exception) {
                output.setLength(offset)
                output.fd.sync()
                throw failure
            }
        }
        entry.offset += bytes.size
    }

    fun verify(entry: Entry, permit: () -> Unit = {}) {
        check(current === entry && entry.offset == entry.source.size &&
            regular(entry.file) && entry.file.length() == entry.source.size) { "暂存文件尚未完整或已失效" }
        if (digest(entry.file, permit) != entry.source.sha256) {
            clear()
            error("源文件摘要校验失败，已清除暂存，请重新读取")
        }
    }

    private fun clear() {
        current?.let { delete(it.file) }
        current = null
        delete(stateFile)
        delete(temporaryState)
        delete(dataFile)
    }
}
