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

    fun providerName(name: String?): String =
        meaningful(name).ifEmpty { "未命名服务商" }

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
    /** Official HookEventName values mapped to task-oriented labels. */
    fun hookEvent(value: String?): String = when (meaningful(value)) {
        "preModelRequest" -> "调用模型前"
        "postModelRequest" -> "模型回复后"
        "preToolUse" -> "工具运行前"
        "permissionRequest" -> "权限申请时"
        "postToolUse" -> "工具运行后"
        "preCompact" -> "整理上下文前"
        "postCompact" -> "整理上下文后"
        "sessionStart" -> "会话开始"
        "sessionEnd" -> "会话结束"
        "userPromptSubmit" -> "提交指令时"
        "subagentStart" -> "协作任务启动"
        "subagentStop" -> "协作任务结束"
        "stop" -> "任务停止"
        "interrupt" -> "任务中断"
        else -> "其他触发事件"
    }

    fun resourceFormat(value: String?): String {
        val kind = meaningful(value).lowercase()
        return when {
            kind.startsWith("text/plain") -> "纯文本"
            kind.contains("markdown") -> "Markdown 文档"
            kind.contains("json") -> "结构化数据"
            kind.startsWith("image/") -> "图片"
            kind.contains("pdf") -> "PDF 文档"
            kind.isBlank() -> "未标明文件类型"
            else -> "文件资源"
        }
    }

    /** 同页去重；保留不同接口返回的不同失败原因。 */
    fun visibleErrors(errors: Map<String, String>, keys: List<String>): List<String> =
        keys.mapNotNull { errors[it] }
            .map(::readableError)
            .distinct()

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
                        "special" -> {
                            val special = target.optJSONObject("value")
                            when (special?.optString("kind")) {
                                "root" -> "整个文件系统"
                                "minimal" -> "运行时基础目录"
                                "project_roots" -> "当前项目目录"
                                "tmpdir", "slash_tmp" -> "临时目录"
                                "unknown" -> "其他路径：" +
                                    workspaceName(special.optString("path"))
                                else -> "未说明范围的系统位置"
                            }
                        }
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

    /** Convert protocol-shaped execution evidence into labels and real task output. */
    fun evidenceSections(source: String): List<Pair<String, String>> {
        val raw = source.trim()
        if (raw.isEmpty()) return listOf("执行详情" to "当前操作没有更多可阅读内容")
        if (raw.startsWith("[")) {
            val events = try { org.json.JSONArray(raw).length() }
                catch (failure: org.json.JSONException) { null }
            if (events != null) return listOf("执行详情" to "记录了 $events 项执行结果")
        }
        val record = try { JSONObject(raw) }
            catch (failure: org.json.JSONException) {
                return listOf("执行内容" to safeEvidenceText(raw))
            }
        val sections = mutableListOf<Pair<String, String>>()
        fun add(key: String, label: String) {
            val value = safeEvidenceText(record.optString(key))
            if (value.isNotEmpty()) sections += label to value
        }
        val status = timelineStatus(record.optString("status"))
        if (status.isNotEmpty()) sections += "执行状态" to status
        if (record.has("exitCode") && !record.isNull("exitCode")) {
            val exitCode = record.optInt("exitCode")
            sections += "执行结果" to
                if (exitCode == 0) "执行成功" else "执行结束，返回码 $exitCode"
        }
        add("command", "执行命令")
        add("summary", "结果摘要")
        add("text", "执行内容")
        val changes = record.optJSONArray("changes")?.length()
            ?: record.optJSONObject("changes")?.length()
        if (changes != null) sections += "文件变更" to "涉及 $changes 个文件"
        val output = sequenceOf("aggregatedOutput", "stdout", "output")
            .map { safeEvidenceText(record.optString(it)) }
            .firstOrNull { it.isNotEmpty() }
        if (output != null) sections += "执行输出" to output
        add("stderr", "错误输出")
        val error = record.optJSONObject("error")?.optString("message")
            ?: record.optString("error").takeUnless { it.startsWith("{") }
        val errorText = safeEvidenceText(error.orEmpty())
        if (errorText.isNotEmpty()) sections += "错误说明" to errorText
        return sections.ifEmpty { listOf("执行详情" to "操作已记录，当前没有更多可阅读内容") }
    }

    /** Preserve useful command output without leaking app-private paths or session identifiers. */
    private fun safeEvidenceText(value: String): String = meaningful(value)
        .replace(
            Regex("""/data/(?:user/[0-9]+|data)/[^\s\n,;)}]+"""),
            "应用私有目录"
        )
        .replace(
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),
            "任务标识"
        ).take(16_000)

    fun readableSkillSummary(description: String?): String {
        val detail = meaningful(description)
        return if (detail.any { it in '\u4e00'..'\u9fff' }) detail.take(100)
        else "可在任务对话中使用，按需启用或停用"
    }
}
