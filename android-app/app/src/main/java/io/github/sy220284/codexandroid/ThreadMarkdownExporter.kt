package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject

/** Default export is the conversation; execution evidence is an explicit separate scope. */
internal object ThreadMarkdownExporter {
    private fun body(item: JSONObject): String {
        if (item.optString("type") == "agentMessage") return AgentReplyContract.visibleText(item)
        val direct = item.optString("text")
        if (direct.isNotEmpty() && !item.isNull("text")) return direct
        val content = item.optJSONArray("content") ?: return ""
        return (0 until content.length()).mapNotNull { content.optJSONObject(it)?.let { part ->
            when (part.optString("type")) {
                "image", "audio" -> if (part.optString("type") == "image") "[图片附件]" else "[音频附件]"
                "localImage", "localAudio" -> if (part.optString("type") == "localImage") "[本地图片]" else "[本地音频]"
                "skill", "mention" -> "[" + part.optString("type").let { if (it == "skill") "技能" else "引用" } +
                    "：" + part.optString("name") + "]"
                else -> part.optString("text").takeUnless { part.isNull("text") }
            }
        } }.joinToString("\n")
    }

    fun render(history: JSONObject, includeActivity: Boolean = false): String {
        val thread = history.optJSONObject("thread")
            ?: throw IllegalArgumentException("无法读取会话正文")
        val turns = thread.optJSONArray("turns") ?: JSONArray()
        val lines = mutableListOf("# Codex 会话记录", "")
        var messages = 0
        fun emit(heading: String, body: String) {
            if (body.isBlank()) return
            lines += "## $heading"
            lines += ""
            lines += body
            lines += ""
            messages++
        }
        var reviewMode = false
        for (turnIndex in 0 until turns.length()) {
            val turn = turns.optJSONObject(turnIndex) ?: continue
            val raw = turn.optJSONArray("items") ?: continue
            val items = (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            val progressive = items.any { it.optString("type") == "agentMessage" && it.optString("phase") == "partial_answer" }
            val pending = StringBuilder()
            fun flush() {
                emit("Codex", pending.toString())
                pending.clear()
            }
            for (item in items) {
                when (item.optString("type")) {
                    "enteredReviewMode", "exitedReviewMode" -> {
                        reviewMode = item.optString("type") == "enteredReviewMode"
                        if (includeActivity) { flush(); activity(item)?.let { (title, text) -> emit(title, text) } }
                    }
                    "userMessage" -> if (!reviewMode) { flush(); emit("用户", body(item)) }
                    "agentMessage" -> {
                        if (item.optString("phase") == "commentary") {
                            if (includeActivity) { flush(); emit("过程回复", body(item)) }
                            continue
                        }
                        if (progressive) pending.append(body(item)) else emit("Codex", body(item))
                    }
                    else -> if (includeActivity) {
                        flush()
                        activity(item)?.let { (title, text) -> emit(title, text) }
                    }
                }
            }
            flush()
        }
        require(messages > 0) { "会话暂无可导出的文字消息" }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    private fun activity(item: JSONObject): Pair<String, String>? {
        val kind = item.optString("type")
        fun field(key: String): String = if (item.isNull(key)) "" else item.optString(key)
        fun fields(vararg keys: Pair<String, String>) = keys.mapNotNull { (key, title) ->
            field(key).takeIf(String::isNotBlank)?.let { "$title：$it" }
        }.joinToString("\n")
        fun fenced(value: String): String {
            if (value.isBlank()) return ""
            val tick = 96.toChar()
            val length = maxOf(3, Regex(tick.toString() + "+").findAll(value).maxOfOrNull { it.value.length + 1 } ?: 3)
            val marker = tick.toString().repeat(length)
            return "\n\n$marker\n$value\n$marker"
        }
        fun contentText(content: JSONArray?): String = content?.let { values ->
            (0 until values.length()).mapNotNull { index ->
                val value = values.optJSONObject(index) ?: return@mapNotNull null
                when (value.optString("type")) {
                    "text", "inputText" -> value.optString("text")
                    "image", "inputImage" -> "[返回图片]"
                    "audio", "inputAudio" -> "[返回音频]"
                    "resource" -> "[嵌入资源：" + (value.optJSONObject("resource")?.optString("uri").orEmpty()) + "]"
                    "resource_link", "resourceLink" -> "[资源链接：" + value.optString("uri") + "]"
                    else -> "[其他工具结果]"
                }
            }.joinToString("\n")
        }.orEmpty()
        val status = fields("status" to "状态")
        return when (kind) {
            "reasoning" -> "公开思考摘要" to (item.optJSONArray("summary")?.let { values ->
                (0 until values.length()).joinToString("\n") { values.optString(it) }
            }.orEmpty())
            "plan" -> "计划" to body(item)
            "commandExecution" -> "命令执行" to (fields("status" to "状态", "cwd" to "目录",
                "exitCode" to "退出码", "durationMs" to "耗时（毫秒）") +
                fenced(field("command")) + fenced(field("aggregatedOutput")))
            "fileChange" -> "文件修改" to (status + (item.optJSONArray("changes")?.let { values ->
                (0 until values.length()).joinToString("\n\n") { index ->
                    val change = values.getJSONObject(index)
                    change.optString("path") + " · " + change.optString("kind") + fenced(change.optString("diff"))
                }
            }.orEmpty()).let { "\n$it" })
            "mcpToolCall", "dynamicToolCall" -> {
                val result = item.optJSONObject("result")
                val structured = result?.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
                "工具调用" to (fields("server" to "服务", "tool" to "工具", "namespace" to "命名空间",
                    "status" to "状态", "success" to "成功", "durationMs" to "耗时（毫秒）") +
                    fenced(field("arguments")) + fenced(contentText(result?.optJSONArray("content"))) +
                    fenced(structured) + fenced(contentText(item.optJSONArray("contentItems"))) +
                    fenced(item.optJSONObject("error")?.optString("message").orEmpty()))
            }
            "functionCallOutput" -> "函数执行结果" to (fields("name" to "名称", "namespace" to "命名空间") + fenced(field("output")))
            "webSearch" -> "网页搜索" to (fields("query" to "查询", "action" to "操作") + fenced(field("results")))
            "imageView", "imageGeneration" -> "图像" to fields("path" to "路径", "savedPath" to "保存位置",
                "status" to "状态", "revisedPrompt" to "图像说明", "failure" to "失败原因")
            "sleep" -> "等待" to fields("durationMs" to "耗时（毫秒）")
            "enteredReviewMode", "exitedReviewMode" -> "审查" to fields("review" to "内容")
            "contextCompaction" -> "上下文整理" to "已记录上下文整理"
            "collabAgentToolCall", "subAgentActivity" -> "协作活动" to fields("tool" to "工具",
                "kind" to "活动类型", "status" to "状态", "model" to "模型", "agentsStates" to "活动状态")
            else -> null
        }
    }
}

