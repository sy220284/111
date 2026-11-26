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
    val parts: List<String> = emptyList(), val total: Int = 0, val applied: Int = 0,
    val sourceTurn: String = ""
) {
    init {
        require(owner.isNotBlank() && scope.matches(Regex("[0-9a-f]{64}")))
        require(parts.size <= 12 && total in 0..12 && applied in 0..parts.size)
        require(parts.size <= MAX_PARTS && parts.all { it.length <= MAX_PART_CHARS } &&
            parts.sumOf { it.length } <= MAX_PARTS * MAX_PART_CHARS)
    }
    val identity get() = digest(JSONArray(listOf(source, mode, question, sourceTurn)).toString())
    val text get() = parts.joinToString("\n\n")
    val complete get() = total > 0 && parts.size == total
    fun encode() = JSONObject().put("owner", owner).put("scope", scope).put("source", source)
        .put("mode", mode).put("question", question).put("parts", JSONArray(parts))
        .put("total", total).put("applied", applied).put("sourceTurn", sourceTurn).toString()

    companion object {
        // Native samples return up to 64 KiB of text; allow JSON/timeline metadata for each of twelve paid responses.
        const val MAX_PARTS = 12
        const val MAX_PART_CHARS = 80_000
        const val MAX_RECORD_BYTES = 8_000_000L
        fun identity(endpoint: String, key: String): String = digest("$endpoint\n$key")
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(text: String): AnalysisCheckpoint {
            val row = JSONObject(text)
            val parts = row.getJSONArray("parts")
            return AnalysisCheckpoint(row.getString("owner"), row.getString("scope"), row.getString("source"),
                row.getString("mode"), row.getString("question"),
                (0 until parts.length()).map(parts::getString), row.getInt("total"), row.getInt("applied"), row.optString("sourceTurn"))
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
    private fun readFile(target: File, owner: String, scope: String): AnalysisCheckpoint? {
        if (!target.exists()) return null
        require(target.length() in 1..AnalysisCheckpoint.MAX_RECORD_BYTES) { "分析记录损坏，原文件已保留" }
        return AnalysisCheckpoint.decode(target.readText()).also {
            require(it.owner == owner && it.scope == scope) { "分析记录归属不匹配" }
        }
    }
    private fun mediaFile(owner: String, scope: String, identity: String): File {
        require(identity.matches(Regex("[0-9a-f]{64}"))) { "分析记录标识无效" }
        return File(directory, file(owner, scope, false).nameWithoutExtension + "-" + identity + ".json")
    }
    /** Old single-record files remain readable; successful writes migrate only that same record. */
    fun listMedia(owner: String, scope: String): List<AnalysisCheckpoint> = synchronized(lock) {
        if (!directory.exists()) return@synchronized emptyList()
        val legacy = file(owner, scope, false)
        val prefix = legacy.nameWithoutExtension + "-"
        val files = requireNotNull(directory.listFiles()) { "无法读取媒体分析记录" }
            .filter { it.name == legacy.name || (it.name.startsWith(prefix) && it.name.endsWith(".json")) }
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        files.mapNotNull { target -> readFile(target, owner, scope)?.also { record ->
            require(target == legacy || target == mediaFile(owner, scope, record.identity)) { "分析记录标识不匹配" }
        } }.distinctBy { it.identity }
    }
    fun readMedia(owner: String, scope: String, identity: String): AnalysisCheckpoint? = synchronized(lock) {
        readFile(mediaFile(owner, scope, identity), owner, scope)?.also {
            require(it.identity == identity) { "分析记录标识不匹配" }
        } ?: readFile(file(owner, scope, false), owner, scope)?.takeIf { it.identity == identity }
    }
    fun read(owner: String, scope: String, screen: Boolean): AnalysisCheckpoint? = synchronized(lock) {
        if (screen) readFile(file(owner, scope, true), owner, scope) else listMedia(owner, scope).firstOrNull()
    }
    fun save(record: AnalysisCheckpoint, screen: Boolean): Unit = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "无法建立分析记录目录" }
        val target = if (screen) file(record.owner, record.scope, true) else mediaFile(record.owner, record.scope, record.identity)
        val existing = if (screen) read(record.owner, record.scope, true) else readMedia(record.owner, record.scope, record.identity)
        val durable = if (existing != null) {
            val longer = if (existing.parts.size > record.parts.size) existing else record
            val shorter = if (existing.parts.size > record.parts.size) record else existing
            require(longer.source == shorter.source && longer.mode == shorter.mode && longer.question == shorter.question &&
                longer.sourceTurn == shorter.sourceTurn && longer.parts.take(shorter.parts.size) == shorter.parts &&
                (screen || longer.total == shorter.total)) { "分析记录不属于同一次任务" }
            longer.copy(applied = maxOf(existing.applied, record.applied))
        } else record
        val temporary = File.createTempFile("analysis-", ".tmp", directory)
        try {
            java.io.FileOutputStream(temporary).use { out ->
                out.write(durable.encode().toByteArray()); out.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            if (!screen) {
                val legacy = file(record.owner, record.scope, false)
                if (readFile(legacy, record.owner, record.scope)?.identity == record.identity)
                    check(legacy.delete()) { "无法迁移旧分析记录" }
            }
        } finally { if (temporary.exists()) check(temporary.delete()) { "无法清理分析临时记录" } }
        Unit
    }
    fun removeMedia(record: AnalysisCheckpoint): Unit = synchronized(lock) {
        val target = mediaFile(record.owner, record.scope, record.identity)
        if (target.exists()) check(target.delete()) { "无法清除分析记录" }
        val legacy = file(record.owner, record.scope, false)
        if (readFile(legacy, record.owner, record.scope)?.identity == record.identity)
            check(legacy.delete()) { "无法清除旧分析记录" }
    }
    /** Explicit recovery only: delete invalid records, never healthy sibling analyses. */
    fun removeDamagedMedia(owner: String, scope: String): Unit = synchronized(lock) {
        val legacy = file(owner, scope, false)
        val prefix = legacy.nameWithoutExtension + "-"
        if (!directory.exists()) return@synchronized
        val files = requireNotNull(directory.listFiles()) { "无法读取分析记录" }
            .filter { it.name == legacy.name || (it.name.startsWith(prefix) && it.name.endsWith(".json")) }
        for (target in files) {
            val valid = try {
                val record = readFile(target, owner, scope)
                record != null && (target == legacy || target == mediaFile(owner, scope, record.identity))
            } catch (_: org.json.JSONException) { false }
              catch (_: IllegalArgumentException) { false }
            if (!valid) check(target.delete()) { "无法清除损坏记录" }
        }
    }
    fun remove(owner: String, scope: String, screen: Boolean): Unit = synchronized(lock) {
        val target = file(owner, scope, screen)
        val targets = if (screen) listOf(target) else {
            val prefix = target.nameWithoutExtension + "-"
            (directory.listFiles()?.filter { it.name == target.name || (it.name.startsWith(prefix) && it.name.endsWith(".json")) }.orEmpty())
        }
        targets.forEach { if (it.exists()) check(it.delete()) { "无法清除分析记录" } }
    }
}
