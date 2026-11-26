package io.github.sy220284.codexandroid

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** One private, process-scoped checkpoint. Callers serialize begin/append/save.
 * No checkpoint is authorized merely by the existence of a cached file.
 */
internal class RemoteTransferCheckpoint(private val directory: File) {
    companion object {
        const val CHUNK_BYTES = 1024 * 1024
        const val MAX_BYTES = 512L * 1024 * 1024
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
            file.inputStream().use { input ->
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

    data class Source(val owner: String, val size: Long, val sha256: String) {
        init {
            require(owner.isNotBlank() && size in 0..MAX_BYTES && isDigest(sha256)) { "无效远端文件摘要或大小" }
        }
    }
    class Entry internal constructor(val source: Source, val file: File, var offset: Long = 0)
    private var current: Entry? = null

    fun begin(source: Source): Entry {
        current?.takeIf { it.source == source && it.file.isFile && it.file.length() == it.offset }?.let { return it }
        clear()
        check(directory.isDirectory || directory.mkdirs()) { "无法建立私有文件暂存目录" }
        // Only this process's in-memory descriptor authorizes resume. Remove stale
        // process-death checkpoints from this dedicated cache, never user files.
        directory.listFiles()?.filter { it.name.startsWith("delivery-") && it.name.endsWith(".part") }
            ?.forEach { check(it.isFile && it.delete()) { "无法清理旧文件暂存" } }
        return Entry(source, File.createTempFile("delivery-", ".part", directory)).also { current = it }
    }

    fun append(entry: Entry, offset: Long, bytes: ByteArray) {
        check(current === entry && offset == entry.offset && entry.file.length() == offset) { "断点归属或位置已变化" }
        val expected = minOf(CHUNK_BYTES.toLong(), entry.source.size - offset)
        require(offset < entry.source.size && bytes.size.toLong() == expected) { "远端分块长度不一致" }
        RandomAccessFile(entry.file, "rw").use { output ->
            try {
                output.seek(offset)
                output.write(bytes)
                output.fd.sync()
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
            entry.file.isFile && entry.file.length() == entry.source.size) { "暂存文件尚未完整或已失效" }
        if (digest(entry.file, permit) != entry.source.sha256) {
            clear()
            error("源文件摘要校验失败，已清除暂存，请重新读取")
        }
    }

    private fun clear() {
        current?.let { check(!it.file.exists() || it.file.delete()) { "无法清除旧文件暂存" } }
        current = null
    }
}
