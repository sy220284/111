package io.github.sy220284.codexandroid

import org.json.JSONArray
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Native queue can snapshot local media into data URLs. Keep the original attachment
 * identity by native client message ID; no reconstruction from rendered chat text. */
internal object MediaHistoryStore {
    private fun id(value: String): String = UUID.fromString(value).toString().also {
        require(it.equals(value, ignoreCase = true)) { "媒体消息标识无效" }
    }
    private fun entry(root: File, clientId: String) = File(File(root, "history"), "message-" + id(clientId) + ".json")

    @Synchronized fun record(root: File, clientId: String, paths: List<String>) {
        if (paths.isEmpty()) return
        val folder = root.canonicalFile
        val names = paths.map { path ->
            val file = File(path).canonicalFile
            require(file.parentFile == folder && file.isFile && file.length() > 0) { "历史媒体原件不属于私有目录" }
            file.name
        }.distinct()
        // Use the same managed-name and count contract as draft recovery.
        val draft = DraftPersistence.decode(DraftPersistence.encode(mapOf("media" to ComposerDraft(images = paths))))
        require(draft.getValue("media").images == names)
        val target = entry(folder, clientId)
        if (target.isFile) {
            require(read(folder, clientId) == names.map { File(folder, it).path }) { "媒体消息标识已绑定不同原件" }
            return
        }
        check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "无法建立媒体历史目录" }
        val temp = File.createTempFile("message-", ".tmp", target.parentFile)
        try {
            java.io.FileOutputStream(temp).use { out ->
                out.write(JSONArray(names).toString().toByteArray()); out.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally { if (temp.exists()) check(temp.delete()) { "无法清理临时媒体历史记录" } }
    }
    @Synchronized fun read(root: File, clientId: String?): List<String> {
        if (clientId.isNullOrBlank()) return emptyList()
        val parsed = try { id(clientId) } catch (_: IllegalArgumentException) { return emptyList() }
        val folder = root.canonicalFile
        val target = entry(folder, parsed)
        if (!target.exists()) return emptyList()
        require(target.length() in 1..4096) { "媒体历史记录损坏" }
        val array = JSONArray(target.readText())
        require(array.length() in 1..LocalImageAttachments.MAX_FILES)
        val paths = (0 until array.length()).map { index ->
            val name = array.getString(index)
            val path = File(folder, name).canonicalFile
            require(path.parentFile == folder && path.name == name) { "媒体历史路径无效" }
            path.path
        }
        val validated = DraftPersistence.decode(DraftPersistence.encode(mapOf("media" to ComposerDraft(images = paths))))
        require(validated.getValue("media").images.size == paths.size)
        return paths
    }
}
