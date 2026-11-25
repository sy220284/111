package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal data class WorkspaceEditorDraft(val base: String, val edited: String)

internal class WorkspaceEditorDraftStore(private val directory: File) {
    private fun entry(root: File, file: File): File {
        val parent = root.canonicalFile.toPath()
        val path = file.canonicalFile.toPath()
        require(path.startsWith(parent) && path != parent) { "文件不在工作区内" }
        val key = parent.relativize(path).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, digest + ".json")
    }
    fun read(root: File, file: File): WorkspaceEditorDraft? {
        val entry = entry(root, file)
        if (!entry.exists()) return null
        val data = JSONObject(entry.readText())
        return WorkspaceEditorDraft(data.getString("base"), data.getString("edited"))
    }
    fun save(root: File, file: File, draft: WorkspaceEditorDraft) {
        val entry = entry(root, file)
        if (draft.base == draft.edited) { clear(root, file); return }
        require(draft.edited.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "草稿超过 1 MB" }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建编辑草稿目录" }
        val temporary = File.createTempFile("editor-", ".tmp", directory)
        try {
            temporary.writeText(JSONObject().put("base", draft.base).put("edited", draft.edited).toString())
            Files.move(temporary.toPath(), entry.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    fun clear(root: File, file: File) {
        val entry = entry(root, file)
        check(!entry.exists() || entry.delete()) { "无法清除编辑草稿" }
    }
}
