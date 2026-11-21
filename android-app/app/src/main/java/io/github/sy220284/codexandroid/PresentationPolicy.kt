package io.github.sy220284.codexandroid

import org.json.JSONObject

/**
 * 唯一的用户可见文本边界。原始 ID、路径及协议值仍由 ViewModel 和 RPC 保存，
 * 页面只消费能帮助用户识别任务、工作区、权限与操作状态的名称。
 */
object PresentationPolicy {
    fun meaningful(value: String?): String {
        val text = value?.trim().orEmpty()
        return text.takeUnless {
            it.isBlank() || it.equals("null", true) ||
                it.equals("undefined", true) || it.equals("none", true)
        }.orEmpty()
    }

    fun threadTitle(name: String?, preview: String?): String =
        meaningful(name).ifEmpty { meaningful(preview).replace('\n', ' ').take(72) }
            .ifEmpty { "未命名会话" }

    fun workspaceName(path: String?): String {
        val value = meaningful(path)
        if (value.isEmpty() ||
            value.startsWith("/data/user/") ||
            value.startsWith("/data/data/") ||
            value.contains("/Android/data/")) return "本机工作区"
        val name = value.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return meaningful(name).ifEmpty { "本机工作区" }
    }

    fun skillName(name: String?): String {
        val raw = meaningful(name)
        if (raw.isEmpty()) return "未命名技能"
        return raw.substringAfterLast(':').replace('-', ' ').replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun approvalMode(value: String?): String = when (meaningful(value)) {
        "untrusted" -> "仅执行可信操作"
        "on-request" -> "按需请求批准"
        "never" -> "由 Codex 按当前规则执行"
        else -> "正在读取"
    }

    fun sandboxMode(value: String?): String = when (meaningful(value)) {
        "read-only" -> "只读"
        "workspace-write" -> "可编辑工作区"
        "danger-full-access" -> "完全访问（高风险）"
        else -> "正在读取"
    }

    fun permissionProfile(value: String?): String = when {
        meaningful(value).endsWith("read-only") -> "只读工作区"
        meaningful(value).endsWith("workspace") ||
            meaningful(value).endsWith("workspace-write") -> "可编辑工作区"
        meaningful(value).endsWith("danger-full-access") -> "完全访问（高风险）"
        else -> "自定义权限方案"
    }

    fun timelineKind(value: String?): String = when (value) {
        "user", "userMessage" -> "你的指令"
        "agentMessage", "agent" -> "Codex 回复"
        "reasoning" -> "思考过程"
        "plan" -> "任务计划"
        "commandExecution" -> "命令执行"
        "fileChange" -> "文件变更"
        "mcpToolCall" -> "工具调用"
        "error" -> "执行异常"
        else -> "任务进展"
    }

    fun timelineStatus(value: String?): String = when (meaningful(value).lowercase()) {
        "completed" -> "已完成"
        "inprogress", "in_progress", "running", "streaming" -> "执行中"
        "failed" -> "失败"
        "declined" -> "已拒绝"
        else -> ""
    }

    fun serviceState(raw: String?): String {
        val value = meaningful(raw).lowercase()
        return when {
            value.contains("failed") || value.contains("error") -> "连接失败"
            value.contains("disconnected") || value.contains("notconnected") -> "未连接"
            value.contains("starting") || value.contains("connecting") -> "正在连接"
            value.contains("running") || value.contains("ready") ||
                value.contains("connected") -> "已连接"
            else -> "未连接"
        }
    }

    fun authorizationState(raw: String?): String {
        val value = meaningful(raw).lowercase()
        return when {
            value.contains("unauthorized") || value.contains("required") ||
                value.contains("notlogged") -> "需要授权"
            value.contains("authorized") || value.contains("authenticated") -> "已授权"
            else -> "待确认"
        }
    }

    /**
     * Read the official AdditionalPermissionProfile schema without exposing RPC keys.
     * Requested paths are reduced to recognizable names when they live in app-private storage.
     */
    fun readableError(value: String?): String {
        val raw = meaningful(value)
        if (raw.isEmpty()) return "当前操作没有返回详细原因"
        return raw.replace(
            Regex("""/data/(?:user/[0-9]+|data)/[^\s\n,;)}]+"""),
            "应用私有目录"
        ).replace(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            "任务标识"
        ).take(500)
    }

    fun permissionDetails(value: JSONObject?): List<String> {
        if (value == null) return listOf("内核没有提供可核对的授权范围")
        val result = mutableListOf<String>()
        val network = value.optJSONObject("network")
        if (network?.optBoolean("enabled") == true)
            result += "允许访问网络"
        else if (network != null)
            result += "网络权限保持当前限制"

        val fileSystem = value.optJSONObject("fileSystem")
        if (fileSystem != null) {
            for ((key, label) in listOf("read" to "读取", "write" to "修改")) {
                val paths = fileSystem.optJSONArray(key)
                if (paths != null) {
                    for (i in 0 until paths.length()) {
                        val path = meaningful(paths.optString(i))
                        if (path.isNotEmpty())
                            result += "$label：" + workspaceName(path)
                    }
                }
            }
            val entries = fileSystem.optJSONArray("entries")
            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val access = when (entry.optString("access")) {
                        "read" -> "读取"
                        "write" -> "修改"
                        "deny" -> "禁止访问"
                        else -> "文件访问"
                    }
                    val target = entry.optJSONObject("path")
                    val name = when (target?.optString("type")) {
                        "path" -> workspaceName(target.optString("path"))
                        "glob_pattern" -> "匹配范围：" +
                            meaningful(target.optString("pattern")).ifEmpty { "指定文件" }
                        "special" -> "指定系统位置"
                        else -> "指定文件"
                    }
                    result += "$access：$name"
                }
            }
            if (fileSystem.has("globScanMaxDepth")) {
                result += "文件搜索深度：" + fileSystem.optInt("globScanMaxDepth")
            }
        }
        val unrecognized = value.keys().asSequence()
            .any { it != "network" && it != "fileSystem" }
        if (unrecognized) result += "包含其他权限要求，请谨慎决定"
        return if (result.isEmpty()) listOf("没有新增权限范围") else result.distinct()
    }

    fun reasoningLabel(value: String?): String = when (meaningful(value).lowercase()) {
        "minimal" -> "极简"
        "low" -> "较快"
        "medium" -> "均衡"
        "high" -> "深入"
        "xhigh" -> "深度"
        else -> "默认"
    }

    fun goalStatus(value: String?): String = when (meaningful(value)) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "blocked" -> "遇到阻碍"
        "complete" -> "已完成"
        else -> "未设置"
    }

    fun readableSkillSummary(description: String?): String {
        val detail = meaningful(description)
        return if (detail.any { it in '\u4e00'..'\u9fff' }) detail.take(100)
        else "可在任务对话中使用，按需启用或停用"
    }
}
