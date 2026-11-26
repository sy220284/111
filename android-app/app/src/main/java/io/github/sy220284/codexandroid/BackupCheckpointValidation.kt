package io.github.sy220284.codexandroid

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** All referenced snapshot objects must be in the archive, with the correct bytes.
 * This deliberately does not interpret project code or use an imported cwd. */
internal object BackupCheckpointValidation {
    fun verify(staged: File) {
        val checkpoint = File(staged, "files/" + AnswerCheckpointFormat.ROOT)
        if (!checkpoint.exists()) return
        val objects = File(checkpoint, AnswerCheckpointFormat.OBJECTS)
        val manifests = File(checkpoint, AnswerCheckpointFormat.MANIFESTS)
        val sizes = mutableMapOf<String, Long>()
        fun entries(folder: File): Array<File> =
            if (!folder.exists()) emptyArray() else requireNotNull(folder.listFiles()) {
                "无法读取备份快照目录"
            }
        for (blob in entries(objects)) {
            require(blob.isFile && AnswerCheckpointFormat.isObjectName(blob.name)) { "快照对象路径无效" }
            if (AnswerCheckpointFormat.digest(blob) != blob.name) throw IOException("快照对象摘要不一致：" + blob.name)
            sizes[blob.name] = blob.length()
        }
        for (manifest in entries(manifests)) {
            require(manifest.isFile && AnswerCheckpointFormat.isManifestName(manifest.name)) { "快照清单路径无效" }
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
