package io.github.sy220284.codexandroid

import org.json.JSONObject

/** The server list is the registry; these names only supply Android presentation and priority. */
internal data class CoreFeatureSwitch(
    val name: String,
    val stage: String,
    val enabled: Boolean,
    val defaultEnabled: Boolean,
    val displayName: String,
    val description: String
) {
    val title: String get() = FeatureSwitchPolicy.names[name] ?: displayName.ifBlank { name.replace('_', ' ') }
    val stageLabel: String get() = when (stage) {
        "stable" -> "稳定"
        "beta" -> "实验"
        "underDevelopment" -> "开发中"
        "deprecated" -> "已弃用"
        "removed" -> "已移除"
        else -> "未知阶段"
    }
    fun restriction(managed: Set<String>): String? = when {
        name in managed -> "由管理策略控制"
        stage == "removed" -> "内核已移除此能力"
        name in FeatureSwitchPolicy.desktopOnly -> "此功能不适用于 Android"
        stage !in setOf("stable", "beta", "underDevelopment", "deprecated") -> "当前版本无法确认是否可修改"
        else -> null
    }
    fun matches(query: String): Boolean = listOf(name, title, description, stageLabel).any {
        it.contains(query.trim(), ignoreCase = true)
    }
}

internal object FeatureSwitchPolicy {
    // Do not use this presentation map as a filter for the live feature registry.
    val preferred = setOf("memories", "recommended_plugins", "suppress_model_change_announcements",
        "code_mode", "code_mode_tool_search", "code_mode_mcp_result_dedup", "mcp_tool_list_changed")
    val desktopOnly = setOf("respect_system_proxy", "tui_extensions_statusline", "prevent_idle_sleep")
    val names = mapOf(
        "memories" to "长期记忆",
        "recommended_plugins" to "推荐相关插件",
        "suppress_model_change_announcements" to "减少模型切换提示",
        "code_mode" to "代码模式",
        "code_mode_tool_search" to "代码模式工具搜索",
        "code_mode_mcp_result_dedup" to "工具结果去重",
        "code_mode_prewarm" to "代码环境预热",
        "restored_history_prewarm" to "历史会话预热",
        "executor_capability_discovery" to "执行环境能力发现",
        "executor_capability_discovery_v2" to "第二代执行环境发现",
        "per_environment_context" to "独立执行环境上下文",
        "multi_env_copy_file" to "跨环境复制文件",
        "multi_environment_mcp" to "多环境 MCP",
        "mcp_tool_list_changed" to "MCP 工具变更感知",
        "apps_early_resource_discovery" to "应用资源提前发现",
        "skill_catalog_deltas" to "技能目录增量更新",
        "incremental_exec_tools" to "执行工具增量加载",
        "local_thread_store_compression" to "本地会话压缩",
        "background_paginated_rollout_migration" to "后台分页迁移历史",
        "async_compaction" to "异步上下文压缩",
        "context_management" to "高级上下文管理",
        "token_budget" to "词元预算管理",
        "default_mode_request_user_input" to "普通模式主动询问",
        "request_permissions_tool" to "按需申请权限",
        "send_message_to_user_async" to "执行中主动回复",
        "current_time_reminder" to "当前时间提醒",
        "nonfatal_clock_read_errors" to "时钟异常不中断任务",
        "artifact" to "结构化产物",
        "runtime_metrics" to "运行性能统计",
        "critical_path_timing" to "任务耗时分析",
        "analytics_plan_history" to "额度使用历史",
        "login_shell_package_path" to "登录 Shell 工具路径",
        "network_proxy" to "网络代理",
        "prevent_idle_sleep" to "防止系统休眠",
        "respect_system_proxy" to "系统代理",
        "tui_extensions_statusline" to "终端扩展状态栏",
        "multi_agent_v2" to "第二代多智能体",
        "multi_agent" to "多智能体",
        "web_search_indexed_default" to "默认索引搜索",
        "gcp_setup_wizard" to "Google Cloud 配置向导",
        "gateway_setup_wizard" to "模型网关配置向导",
        "in_app_voice" to "应用内语音",
        "in_app_browser" to "应用内浏览器",
        "image_generation" to "图片生成",
        "hooks" to "任务钩子",
        "fast_mode" to "快速模式"
    )
    fun parse(item: JSONObject): CoreFeatureSwitch {
        val key = item.getString("name")
        require(key.matches(Regex("[A-Za-z0-9_]+"))) { "内核返回了无法安全写入的功能名称" }
        return CoreFeatureSwitch(key, item.getString("stage"), item.getBoolean("enabled"),
            item.getBoolean("defaultEnabled"), item.value("displayName"), item.value("description"))
    }
    fun confirmation(row: CoreFeatureSwitch, enabled: Boolean): String {
        val extra = when {
            row.name == "memories" && enabled -> "开启后可能使用历史会话生成长期记忆，并产生额外模型调用。"
            row.name == "memories" -> "停用会暂停记忆功能；已有记忆可在全局记忆管理中单独处理。"
            row.name.contains("agent") && enabled -> "该能力可能允许派生智能体及额外模型调用，仍需遵守任务授权。"
            row.name in setOf("async_compaction", "token_budget", "context_management") ->
                "上下文功能依赖模型服务和相关设置，可能影响任务恢复或压缩结果。"
            row.stage != "stable" -> "该能力尚未达到稳定状态，可能受平台、账号或其他功能限制。"
            else -> "变更将保存到全局配置，项目策略仍可能改变会话的实际生效状态。"
        }
        return extra + " 部分能力需要重新连接或新建会话。"
    }
    fun writeParams(name: String, enabled: Boolean, version: String?): JSONObject {
        require(name.matches(Regex("[A-Za-z0-9_]+")))
        return JSONObject().put("edits", org.json.JSONArray().put(
            JSONObject().put("keyPath", "features.$name").put("value", enabled).put("mergeStrategy", "replace")
        )).put("reloadUserConfig", true).apply { version?.let { put("expectedVersion", it) } }
    }
}
