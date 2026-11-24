package io.github.sy220284.codexandroid

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** History and cutoff are native identities. Never reconstruct a prompt from rendered text. */
internal class MessageReplayCoordinator(
    private val request: suspend (String, JSONObject) -> JSONObject
) {
    suspend fun readInput(threadId: String, turnId: String): JSONArray {
        require(threadId.isNotBlank() && turnId.isNotBlank()) { "缺少历史回合标识" }
        var cursor: String? = null
        val seen = hashSetOf<String>()
        do {
            val params = JSONObject().put("threadId", threadId).put("turnId", turnId)
                .put("sortDirection", "asc").put("limit", 100)
            cursor?.let { params.put("cursor", it) }
            val page = request("thread/items/list", params)
            val rows = page.getJSONArray("data")
            for (index in 0 until rows.length()) {
                val entry = rows.getJSONObject(index)
                require(entry.getString("turnId") == turnId) { "历史条目所属回合不一致" }
                val item = entry.getJSONObject("item")
                if (item.getString("type") == "userMessage") return item.getJSONArray("content")
            }
            cursor = historyCursor(page)
            if (cursor != null) check(seen.add(cursor)) { "历史分页重复，请重新读取" }
        } while (cursor != null)
        error("该回合没有可重新提交的原始指令")
    }

    fun validateInput(input: JSONArray): List<String> {
        require(input.length() > 0) { "原始指令为空" }
        val images = mutableListOf<String>()
        for (index in 0 until input.length()) {
            val part = input.getJSONObject(index)
            when (part.getString("type")) {
                "text", "image", "audio", "mention" -> Unit
                "localImage", "localAudio", "skill" -> {
                    val path = part.getString("path")
                    require(File(path).isAbsolute && File(path).isFile) { "原始附件或引用已失效，请重新添加后发送" }
                    if (part.getString("type") == "localImage") images += path
                }
                else -> error("原始指令包含当前版本无法重放的内容")
            }
        }
        return images
    }

    /** beforeTurnId is an experimental field of the locked Rust ThreadForkParams. */
    suspend fun forkBefore(threadId: String, turnId: String): String {
        require(threadId.isNotBlank() && turnId.isNotBlank()) { "缺少历史回合标识" }
        val params = JSONObject().put("threadId", threadId).put("beforeTurnId", turnId)
            .put("excludeTurns", true)
        val id = request("thread/fork", params).getJSONObject("thread").getString("id")
        check(id.isNotBlank() && id != threadId) { "内核未返回独立分支" }
        return id
    }
}
