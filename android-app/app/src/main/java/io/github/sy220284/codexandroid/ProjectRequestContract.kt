package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Exact Codex app-server v2 project contracts; roots refer only to vetted local dirs.
 */
object ProjectRequestContract {
    fun threadStart(cwd: File, projectId: String?, model: String, modelProvider: String? = null): JSONObject {
        require(cwd.isDirectory && cwd.isAbsolute) { "任务工作目录不可用" }
        return JSONObject().put("cwd", cwd.canonicalPath).apply {
            if (!projectId.isNullOrBlank()) put("projectId", projectId)
            if (model.isNotBlank()) put("model", model)
            if (!modelProvider.isNullOrBlank()) put("modelProvider", modelProvider)
        }
    }

    private fun rootArray(root: File, folder: File): JSONArray =
        JSONArray().put(JSONObject().put("path",
            WorkspaceSelectionContract.validate(root, folder).absolutePath))

    fun create(root: File, folder: File, name: String, idempotencyKey: String = UUID.randomUUID().toString()): JSONObject {
        require(name.trim().isNotEmpty()) { "项目名称不能为空" }
        require(idempotencyKey.isNotBlank()) { "项目创建标识不能为空" }
        return JSONObject().put("name", name.trim())
            .put("roots", rootArray(root, folder))
            .put("idempotencyKey", idempotencyKey)
    }

    fun importCurrentThread(root: File, folder: File, name: String,
                            threadId: String, idempotencyKey: String = UUID.randomUUID().toString()): JSONObject {
        require(threadId.isNotBlank()) { "需要先选择现有任务会话" }
        return create(root, folder, name, idempotencyKey)
            .put("threads", JSONArray().put(threadId))
    }

    /** Update only root paths while preserving the server's name, metadata and project identity. */
    fun relocate(id: String, roots: JSONArray): JSONObject {
        require(id.isNotBlank() && roots.length() > 0) { "项目迁移参数不完整" }
        return JSONObject().put("projectId", id).put("roots", roots)
    }

    fun rename(id: String, newName: String): JSONObject {
        require(id.isNotBlank() && newName.trim().isNotEmpty()) { "请提供项目和名称" }
        return JSONObject().put("projectId", id).put("name", newName.trim())
    }

    fun read(id: String): JSONObject {
        require(id.isNotBlank()) { "项目标识不能为空" }
        return JSONObject().put("projectId", id)
    }

    fun move(id: String, beforeId: String?): JSONObject {
        require(id.isNotBlank()) { "项目标识不能为空" }
        return JSONObject().put("projectId", id)
            .put("beforeProjectId", beforeId ?: JSONObject.NULL)
    }
}
