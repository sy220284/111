package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Pre-turn file baselines are private. Every writable regenerated version has its own directory. */
internal class AnswerWorkspaceStore(private val root: File, private val checkpoints: File) {
    data class Prepared(val directory: File, val originalWorkspace: String)
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
        return File(checkpoints, name).takeIf { it.resolve("files").isDirectory && it.resolve("cwd").isFile }
    }
    fun hasBaseline(id: String?) = stored(id) != null
    suspend fun checkpoint(clientId: String, workspace: String) = withContext(Dispatchers.IO) {
        val source = WorkspaceSelectionContract.validate(root, File(workspace))
        check(checkpoints.isDirectory || checkpoints.mkdirs()) { "无法创建回答文件快照目录" }
        val target = File(checkpoints, key(clientId))
        if (stored(clientId) == null) {
            val stage = File(checkpoints, ".stage-" + UUID.randomUUID())
            check(stage.mkdir()) { "无法创建文件快照" }
            try {
                publishCopy(source, stage.resolve("files"))
                stage.resolve("cwd").writeText(source.absolutePath)
                check(!target.exists() && stage.renameTo(target)) { "无法提交执行前文件快照" }
            } finally { if (stage.exists()) check(stage.deleteRecursively()) { "无法清理未完成的文件快照" } }
        }
    }
    suspend fun prepare(clientId: String?, workspace: String, allowCurrent: Boolean): Prepared = withContext(Dispatchers.IO) {
        val original = WorkspaceSelectionContract.validate(root, File(workspace))
        val baseline = stored(clientId)
        require(baseline != null || allowCurrent) { "该回合没有执行前文件快照，请确认使用当前文件状态" }
        val versions = File(root, VERSIONS)
        check(versions.isDirectory || versions.mkdirs()) { "无法建立回答独立工作目录" }
        val target = File(versions, UUID.randomUUID().toString())
        publishCopy(baseline?.resolve("files") ?: original, target)
        Prepared(target, baseline?.resolve("cwd")?.readText() ?: original.absolutePath)
    }
    private fun publishCopy(source: File, target: File) {
        val stage = File(target.parentFile, ".stage-" + UUID.randomUUID())
        check(stage.mkdir()) { "无法创建文件快照" }
        try {
            fun copy(from: File, to: File) {
                require(!Files.isSymbolicLink(from.toPath())) { "工作区包含符号链接，无法安全复制：${from.name}" }
                if (from.isDirectory) {
                    check(to.isDirectory || to.mkdir()) { "无法创建目录：${to.name}" }
                    val children = requireNotNull(from.listFiles()) { "无法读取目录：${from.name}" }
                    children.filterNot { it.canonicalFile == File(root, VERSIONS).canonicalFile }.forEach { copy(it, File(to, it.name)) }
                    check(requireNotNull(from.listFiles()).map { it.name }.toSet() == children.map { it.name }.toSet()) { "复制期间工作区内容变化，请重试" }
                } else {
                    require(from.isFile && from.name != ".git") { "工作区包含特殊文件或外部 Git 工作树：${from.name}" }
                    val size = from.length()
                    val modified = from.lastModified()
                    check(to.parentFile!!.usableSpace >= size) { "剩余存储空间不足，无法保存文件快照" }
                    Files.copy(from.toPath(), to.toPath())
                    check(to.length() == size && from.length() == size && from.lastModified() == modified) { "复制期间文件变化：${from.name}" }
                }
            }
            copy(source, stage)
            check(!target.exists() && stage.renameTo(target)) { "无法提交文件快照" }
        } finally { if (stage.exists()) check(stage.deleteRecursively()) { "无法清理未完成的文件快照" } }
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
