package io.github.sy220284.codexandroid

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Exact inventory of bytes, separate from ZIP CRC and from logical data validation.
 * This detects accidental corruption or missing entries, not archive authorship. */
internal object BackupIntegrity {
    const val ENTRY = "integrity.sha256"
    const val STAGED_ENTRY = ".verified-inventory"
    const val MAX_ENTRIES = 15000
    private const val MAGIC = 0x31313134
    private const val MAX_NAME = 4096
    private const val MAX_BYTES = 256L * 1024 * 1024
    private const val MAX_TOTAL = 1024L * 1024 * 1024
    data class Record(val size: Long, val sha256: String)

    class Accumulator {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        fun update(bytes: ByteArray, count: Int) {
            require(count in 0..bytes.size && size + count <= MAX_BYTES) { "备份文件超出大小限制" }
            size += count
            digest.update(bytes, 0, count)
        }
        fun finish() = Record(size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun validate(name: String, record: Record) {
        require(name.length in 1..MAX_NAME && name.none(Char::isISOControl)) { "备份清单路径无效" }
        require(record.size in 0..MAX_BYTES && record.sha256.matches(Regex("[a-f0-9]{64}"))) { "备份摘要无效" }
    }

    fun write(output: OutputStream, records: Map<String, Record>) {
        require(records.size in 1..MAX_ENTRIES && records.values.sumOf { it.size } <= MAX_TOTAL)
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(records.size)
        for ((name, record) in records.toSortedMap()) {
            validate(name, record)
            data.writeUTF(name)
            data.writeLong(record.size)
            data.writeUTF(record.sha256)
        }
        data.flush()
    }

    fun verify(input: InputStream, records: Map<String, Record>) {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC) { "备份完整性清单格式错误" }
        val count = data.readInt()
        require(count in 1..MAX_ENTRIES && count == records.size) { "备份完整性清单数量不符" }
        val seen = hashSetOf<String>()
        repeat(count) {
            val name = data.readUTF()
            val record = Record(data.readLong(), data.readUTF())
            validate(name, record)
            require(seen.add(name) && records[name] == record) { "备份文件缺失或摘要不符：$name" }
        }
        require(data.read() == -1) { "备份完整性清单包含多余数据" }
    }

    fun snapshot(files: List<Pair<String, File>>): Map<String, Record> {
        require(files.size in 1..MAX_ENTRIES)
        val records = linkedMapOf<String, Record>()
        var total = 0L
        for ((name, file) in files) {
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) { "备份文件无效" }
            val accumulator = Accumulator()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_TOTAL) { "备份数据超出大小限制" }
                    accumulator.update(buffer, n)
                }
            }
            val record = accumulator.finish()
            validate(name, record)
            require(records.put(name, record) == null) { "备份文件重复" }
        }
        return records
    }
}
