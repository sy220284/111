package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Immutable content-addressed checkpoints; writable versions never share physical files. */
internal class AnswerWorkspaceStore(private val root: File, private val checkpoints: File) {
    data class Prepared(val directory: File, val originalWorkspace: String)
    private val lock = Mutex()
    private val blobs = File(checkpoints, "objects")
    private val manifests = File(checkpoints, "manifests")
    companion object {
        private const val VERSIONS = ".111-answer-workspaces"
        fun isolated(root: File, path: String): Boolean =
            File(path).canonicalPath.startsWith(File(root, VERSIONS).canonicalPath + File.separator)
        fun permission(root: File, cwd: String, selected: String?): String? =
            if (isolated(root, cwd)) if (selected == ":read-only") selected else ":workspace" else selected
    }
    private fun key(id: String): String = UUID.fromString(id).toString()
    private fun stored(id: String?): File? {
        if (id == null) return null
        val name = try { key(id) } catch (_: IllegalArgumentException) { return null }
        return File(manifests, "$name.json").takeIf { it.isFile }
    }
    fun hasBaseline(id: String?) = stored(id) != null
    private fun ensureStore() {
        check(blobs.isDirectory || blobs.mkdirs()) { "无法创建文件快照对象目录" }
        check(manifests.isDirectory || manifests.mkdirs()) { "无法创建文件快照清单目录" }
    }
    suspend fun checkpoint(clientId: String, workspace: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val source = WorkspaceSelectionContract.validate(root, File(workspace))
            ensureStore()
            if (stored(clientId) == null) saveManifest(clientId, scan(source))
        }
    }
    suspend fun prepare(clientId: String?, workspace: String, allowCurrent: Boolean,
        nextClientId: String? = null): Prepared = withContext(Dispatchers.IO) {
        lock.withLock {
            val original = WorkspaceSelectionContract.validate(root, File(workspace))
            val baseline = stored(clientId)
            require(baseline != null || allowCurrent) { "该回合没有执行前文件快照，请确认使用当前文件状态" }
            ensureStore()
            val manifest = baseline?.let { JSONObject(it.readText()) } ?: scan(original)
            val versions = File(root, VERSIONS)
            check(versions.isDirectory || versions.mkdirs()) { "无法建立回答独立工作目录" }
            val target = File(versions, UUID.randomUUID().toString())
            materialize(manifest, target)
            try {
                if (nextClientId != null) saveManifest(nextClientId, JSONObject(manifest.toString()).put("cwd", target.absolutePath))
            } catch (failure: Exception) {
                check(target.deleteRecursively()) { "无法清理尚未使用的回答工作目录" }
                throw failure
            }
            Prepared(target, manifest.getString("cwd"))
        }
    }
    private fun saveManifest(id: String, manifest: JSONObject) {
        val target = File(manifests, key(id) + ".json")
        val stage = Files.createTempFile(manifests.toPath(), ".stage-", ".tmp").toFile()
        try {
            stage.writeText(manifest.toString())
            check(!target.exists() && stage.renameTo(target)) { "无法提交执行前文件快照" }
        } finally { Files.deleteIfExists(stage.toPath()) }
    }
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        return sha.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun scan(source: File): JSONObject {
        val rows = JSONArray()
        val verify = mutableListOf<() -> Unit>()
        val created = mutableListOf<File>()
        fun children(file: File) = requireNotNull(file.listFiles()) { "无法读取目录：${file.name}" }
            .filterNot { it.canonicalFile == File(root, VERSIONS).canonicalFile }
        try {
            fun visit(file: File) {
                require(!Files.isSymbolicLink(file.toPath())) { "工作区包含符号链接，无法安全复制：${file.name}" }
                val path = file.relativeTo(source).invariantSeparatorsPath
                if (file.isDirectory) {
                    if (path.isNotEmpty()) rows.put(JSONObject().put("path", path).put("directory", true))
                    val entries = children(file)
                    val names = entries.map { it.name }.toSet()
                    verify += { check(children(file).map { it.name }.toSet() == names) { "快照期间目录内容变化，请重试" } }
                    entries.forEach(::visit)
                } else {
                    require(file.isFile && file.name != ".git") { "工作区包含特殊文件或外部 Git 工作树：${file.name}" }
                    val size = file.length()
                    val modified = file.lastModified()
                    val hash = digest(file)
                    val blob = File(blobs, hash)
                    if (!blob.isFile) {
                        check(blobs.usableSpace >= size) { "剩余存储空间不足，无法保存文件快照" }
                        val stage = Files.createTempFile(blobs.toPath(), ".stage-", ".tmp").toFile()
                        try {
                            Files.copy(file.toPath(), stage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            check(stage.length() == size && digest(stage) == hash) { "复制期间文件变化：${file.name}" }
                            check(stage.renameTo(blob)) { "无法保存文件快照对象" }
                            created += blob
                        } finally { Files.deleteIfExists(stage.toPath()) }
                    }
                    check(blob.length() == size) { "文件快照对象损坏" }
                    verify += { check(file.isFile && file.length() == size && file.lastModified() == modified) { "快照期间文件变化：${file.name}" } }
                    rows.put(JSONObject().put("path", path).put("hash", hash).put("size", size))
                }
            }
            visit(source)
            verify.forEach { it() }
            return JSONObject().put("cwd", source.absolutePath).put("entries", rows)
        } catch (failure: Exception) {
            created.forEach { Files.deleteIfExists(it.toPath()) }
            throw failure
        }
    }
    private fun materialize(manifest: JSONObject, target: File) {
        val stage = File(target.parentFile, ".stage-" + UUID.randomUUID())
        check(stage.mkdir()) { "无法创建回答工作目录" }
        try {
            val entries = manifest.getJSONArray("entries")
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val relative = entry.getString("path")
                require(relative.isNotBlank() && relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "快照路径无效" }
                val file = File(stage, relative)
                require(file.canonicalPath.startsWith(stage.canonicalPath + File.separator)) { "快照路径越界" }
                if (entry.optBoolean("directory")) {
                    check(file.isDirectory || file.mkdirs()) { "无法建立目录：${file.name}" }
                } else {
                    val hash = entry.getString("hash")
                    require(hash.matches(Regex("[a-f0-9]{64}"))) { "快照对象编号无效" }
                    val blob = File(blobs, hash)
                    val size = entry.getLong("size")
                    check(blob.isFile && blob.length() == size && file.parentFile!!.usableSpace >= size) { "快照对象缺失或可用空间不足" }
                    Files.copy(blob.toPath(), file.toPath())
                    check(file.length() == size && digest(file) == hash) { "回答文件快照校验失败：${file.name}" }
                }
            }
            check(!target.exists() && stage.renameTo(target)) { "无法提交独立工作目录" }
        } finally { if (stage.exists()) check(stage.deleteRecursively()) { "无法清理未完成的工作目录" } }
    }
    /** Remap typed paths and explicit local absolute references in the original user input. */
    fun remap(input: JSONArray, source: String, target: String): JSONArray = JSONArray().apply {
        val old = File(source).canonicalPath
        for (i in 0 until input.length()) {
            val part = JSONObject(input.getJSONObject(i).toString())
            if (part.has("path")) {
                val path = part.getString("path")
                if (path.startsWith(old + File.separator)) {
                    val mapped = target + path.removePrefix(old)
                    require(File(mapped).isFile) { "独立工作目录中缺少原始引用：${File(path).name}" }
                    part.put("path", mapped)
                }
            }
            if (part.optString("type") == "text") part.put("text", part.getString("text").replace(old + File.separator, target + File.separator))
            put(part)
        }
    }
}
