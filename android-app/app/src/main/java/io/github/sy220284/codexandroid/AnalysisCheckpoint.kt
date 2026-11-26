package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only completed provider responses are checkpointed. A retry is always a new user decision. */
internal data class AnalysisCheckpoint(
    val owner: String, val scope: String, val source: String, val mode: String, val question: String,
    val parts: List<String> = emptyList(), val total: Int = 0, val applied: Int = 0
) {
    init {
        require(owner.isNotBlank() && scope.matches(Regex("[0-9a-f]{64}")))
        require(parts.size <= 12 && total in 0..12 && applied in 0..parts.size)
        require(parts.sumOf { it.length } <= 300_000)
    }
    val text get() = parts.joinToString("\n\n")
    val complete get() = total > 0 && parts.size == total
    fun encode() = JSONObject().put("owner", owner).put("scope", scope).put("source", source)
        .put("mode", mode).put("question", question).put("parts", JSONArray(parts))
        .put("total", total).put("applied", applied).toString()

    companion object {
        fun identity(endpoint: String, key: String): String = digest("$endpoint\n$key")
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(text: String): AnalysisCheckpoint {
            val row = JSONObject(text)
            val parts = row.getJSONArray("parts")
            return AnalysisCheckpoint(row.getString("owner"), row.getString("scope"), row.getString("source"),
                row.getString("mode"), row.getString("question"),
                (0 until parts.length()).map(parts::getString), row.getInt("total"), row.getInt("applied"))
        }
    }
}

/** Call on IO, inside the existing local mutation lease. Accounts and conversations have separate files. */
internal class AnalysisCheckpointStore(private val directory: File) {
    // Controllers and restore readers may hold separate store objects for the same directory.
    // The read/merge/atomic-replace transaction must share one lock across those objects.
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private companion object {
        val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    }
    private fun file(owner: String, scope: String, screen: Boolean) = File(directory,
        AnalysisCheckpoint.digest("$owner\n$scope\n$screen") + ".json")
    fun read(owner: String, scope: String, screen: Boolean): AnalysisCheckpoint? = synchronized(lock) {
        val target = file(owner, scope, screen)
        if (!target.exists()) return@synchronized null
        require(target.length() in 1..2_000_000) { "分析记录损坏，原文件已保留" }
        AnalysisCheckpoint.decode(target.readText()).also {
            require(it.owner == owner && it.scope == scope) { "分析记录归属不匹配" }
        }
    }
    fun save(record: AnalysisCheckpoint, screen: Boolean): Unit = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "无法建立分析记录目录" }
        val target = file(record.owner, record.scope, screen)
        val existing = if (screen) read(record.owner, record.scope, true) else null
        val durable = if (existing != null) {
            val longer = if (existing.parts.size > record.parts.size) existing else record
            val shorter = if (existing.parts.size > record.parts.size) record else existing
            require(longer.parts.take(shorter.parts.size) == shorter.parts) { "屏幕分析记录不属于同一次采集" }
            longer.copy(applied = maxOf(existing.applied, record.applied))
        } else record
        val temporary = File.createTempFile("analysis-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temporary).use { out ->
                out.write(durable.encode().toByteArray()); out.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { if (temporary.exists()) check(temporary.delete()) { "无法清理分析临时记录" } }
        Unit
    }
    fun remove(owner: String, scope: String, screen: Boolean): Unit = synchronized(lock) {
        val target = file(owner, scope, screen)
        if (target.exists()) check(target.delete()) { "无法清除分析记录" }
    }
}
