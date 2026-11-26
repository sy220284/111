package io.github.sy220284.codexandroid

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** All referenced snapshot objects must be in the archive, with the correct bytes.
 * This deliberately does not interpret project code or use an imported cwd. */
internal object BackupCheckpointValidation {
    fun verify(staged: File) = verifyStore(File(staged, "files/" + AnswerCheckpointFormat.ROOT))

    fun verifyStore(checkpoint: File) {
        if (!checkpoint.exists()) return
        require(checkpoint.isDirectory && !Files.isSymbolicLink(checkpoint.toPath())) { "快照根目录无效" }
        val objects = File(checkpoint, AnswerCheckpointFormat.OBJECTS)
        val manifests = File(checkpoint, AnswerCheckpointFormat.MANIFESTS)
        val sizes = mutableMapOf<String, Long>()
        // Match the archive policy. A process killed during snapshot creation
        // may leave an unpublished .stage-* file; it is not portable content.
        fun entries(folder: File, accepts: (String) -> Boolean): List<File> {
            if (!folder.exists()) return emptyList()
            require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) { "快照目录无效" }
            return requireNotNull(folder.listFiles()) { "无法读取备份快照目录" }
                .filter { accepts(it.name) }
                .onEach { require(it.isFile && !Files.isSymbolicLink(it.toPath())) { "快照文件路径无效" } }
        }
        for (blob in entries(objects, AnswerCheckpointFormat::isObjectName)) {
            if (AnswerCheckpointFormat.digest(blob) != blob.name) throw IOException("快照对象摘要不一致：" + blob.name)
            sizes[blob.name] = blob.length()
        }
        for (manifest in entries(manifests, AnswerCheckpointFormat::isManifestName)) {
            require(manifest.length() <= 16L * 1024 * 1024) { "快照清单超出安全大小" }
            val content = JSONObject(manifest.readText())
            require(content.getString("cwd").isNotBlank()) { "快照缺少工作区标识" }
            val rows = content.getJSONArray("entries")
            require(rows.length() <= 15000) { "快照文件数量超出安全上限" }
            val paths = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val entry = rows.getJSONObject(i)
                val path = entry.getString("path")
                require(BackupDataPolicy.safeSyntax("files/projects/" + path) &&
                    path.isNotBlank() && paths.add(path)) { "快照路径无效或重复" }
                if (!entry.optBoolean("directory")) {
                    val hash = entry.getString("hash")
                    val size = entry.getLong("size")
                    if (!AnswerCheckpointFormat.isObjectName(hash) || size < 0 || sizes[hash] != size)
                        throw IOException("快照清单引用的对象缺失或大小不一致：" + manifest.name)
                }
            }
        }
    }
}
