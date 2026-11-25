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
        !FeatureSwitchPolicy.isWritableName(name) -> "此历史标识无法直接写入配置"
        stage !in setOf("stable", "beta", "underDevelopment", "deprecated") -> "当前版本无法确认是否可修改"
        else -> null
    }
}

internal object FeatureSwitchPolicy {
    // Do not use this presentation map as a filter for the live feature registry.
    val preferred = setOf("memories", "recommended_plugins", "suppress_model_change_announcements",
        "code_mode", "code_mode_tool_search", "code_mode_mcp_result_dedup", "mcp_tool_list_changed")
    val desktopOnly = setOf("respect_system_proxy", "tui_extensions_statusline", "prevent_idle_sleep",
        "windows_sandbox_service", "powershell_shell_version", "prefer_mxc", "tui_workspace", "tui_multi_backend")
    fun isWritableName(name: String) = name.matches(Regex("[A-Za-z0-9_]+"))

    /** Mirrors config/src/merge.rs and features/src/lib.rs of the locked native core.
     * Boolean shorthand and structured tables are both valid persisted representations. */
    internal fun persistedEnabled(features: JSONObject?, name: String): Boolean? {
        val raw = features?.opt(name)
        if (raw is Boolean) return raw
        if (raw !is JSONObject) return null
        val field = when (name) {
            "send_message_to_user_async" -> "root_agent"
            "code_mode", "guardianv3", "multi_agent_v2", "network_proxy",
            "sleep_tool", "runtime_agent_instances" -> "enabled"
            else -> return null
        }
        return raw.opt(field) as? Boolean
    }

    internal fun verifyWrite(write: JSONObject, persisted: Boolean?, effective: Boolean,
                             requested: Boolean): Boolean {
        val status = write.getString("status")
        check(status == "ok" || status == "okOverridden") { "内核返回未知配置写入状态：$status" }
        check(persisted == requested) { "用户配置未回读到写入值，请重新核对" }
        return status == "ok" && effective == requested
    }
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
        "fast_mode" to "快速模式",
        "cli_resume_account_check" to "恢复会话时检查账号",
        "statsig_gates" to "服务端功能分流",
        "daemon_auto_start" to "后台服务自动启动",
        "tui_workspace" to "桌面终端工作区",
        "tui_multi_backend" to "桌面终端多后端",
        "app_server_websocket_compression" to "服务端 WebSocket 压缩",
        "shell_tool" to "Shell 命令工具",
        "view_image" to "查看图片",
        "sleep_tool" to "等待工具",
        "secret_auth_storage" to "安全凭据存储",
        "unified_exec" to "统一命令执行",
        "unified_exec_tty" to "交互式终端执行",
        "shell_snapshot" to "Shell 环境快照",
        "powershell_shell_version" to "PowerShell 版本选择",
        "shell_snapshot_v2" to "第二代 Shell 快照",
        "deferred_executor" to "按需启动执行器",
        "cwd_relative_turn_diffs" to "工作目录相对差异",
        "content_item_kinds" to "内容类型标记",
        "untrusted_as_tool_output" to "不可信内容按工具输出处理",
        "executed_tool_call_metadata" to "工具调用执行元数据",
        "connector_tool_result_passthrough" to "连接器结果直传",
        "code_mode_host" to "代码模式运行宿主",
        "code_mode_host_output_truncation" to "代码模式宿主输出截断",
        "code_mode_interrupt" to "代码模式中断",
        "instant_interrupt" to "即时中断",
        "interrupt_on_cancel" to "取消时中断",
        "code_mode_only" to "仅代码模式",
        "code_mode_only_strict_3p_tools" to "第三方工具严格代码模式",
        "standalone_web_search" to "独立网页搜索",
        "external_agent_memory_import" to "外部智能体记忆导入",
        "realtime_frames_local" to "本地实时语音帧",
        "chronicle" to "活动时间记录",
        "apply_patch_streaming_events" to "补丁进度流式事件",
        "exec_permission_approvals" to "命令执行权限审批",
        "write_stdin_approval" to "终端输入审批",
        "windows_sandbox_service" to "Windows 沙箱服务",
        "prefer_mxc" to "优先 MXC 执行环境",
        "api_key_model_discovery" to "API 密钥模型发现",
        "cli_daybreak" to "Daybreak 命令行能力",
        "api_key_cyber_access_programs" to "API 密钥安全访问计划",
        "daybreak_program_propagation" to "Daybreak 计划传递",
        "enable_request_compression" to "请求压缩",
        "unbounded_connection_retries" to "无限连接重试",
        "credential_masking" to "凭据遮蔽",
        "system_proxy_fallback" to "系统代理回退",
        "model_catalog_in_context" to "模型目录加入上下文",
        "multi_agent_v2_dynamic_tools" to "多智能体动态工具",
        "subagent_thread_environments" to "子智能体会话环境",
        "subagent_default_context_limits" to "子智能体上下文限制",
        "defer_mailbox_preemption" to "延迟消息抢占",
        "wake_on_agent_messages" to "智能体消息唤醒",
        "agent_message_board" to "智能体消息板",
        "agent_message_board_mcp" to "智能体消息板 MCP",
        "apps" to "连接应用",
        "catalog_apps_instructions" to "应用目录说明",
        "psp" to "PSP 协议能力",
        "enable_mcp_apps" to "MCP 应用",
        "mcp_2026_07_28" to "MCP 2026-07-28 协议",
        "mcp_connection_sharing" to "MCP 连接共享",
        "mcp_shared_repl_cwd" to "MCP 共享执行目录",
        "mcp_lazy_stdio_startup" to "MCP 延迟启动",
        "codex_apps_mcp_2026_07_28" to "Codex 应用 MCP 新协议",
        "mcp_oauth_refresh_coordination" to "MCP 授权刷新协调",
        "use_xaa" to "XAA 授权",
        "deferred_tool_world_state" to "延迟工具状态同步",
        "command_sessions_world_state" to "命令会话状态同步",
        "incremental_tools" to "工具增量加载",
        "incremental_tools_legacy" to "旧版工具增量加载",
        "incremental_tools_legacy_gcp" to "GCP 旧版工具增量加载",
        "prediction_preserve_parent_tools" to "预测保留父级工具",
        "non_prefixed_mcp_tool_names" to "MCP 工具名称不加前缀",
        "tool_suggest" to "工具推荐",
        "plugins" to "插件",
        "simplified_selection_hints" to "精简选择提示",
        "skip_host_skill_discovery" to "跳过宿主技能发现",
        "browser_annotation_api" to "浏览器标注接口",
        "in_app_chat" to "应用内聊天",
        "in_app_dictation" to "应用内语音转文字",
        "in_app_local_automation" to "应用内本地自动化",
        "in_app_updates" to "应用内更新",
        "daemon_updates" to "后台服务更新",
        "browser_use" to "浏览器操作",
        "browser_use_full_cdp_access" to "浏览器完整 CDP 访问",
        "browser_use_external" to "外部浏览器操作",
        "computer_use" to "计算机操作",
        "remote_plugin" to "远程插件",
        "plugin_sharing" to "插件共享",
        "defer_image_generation" to "延迟图片生成",
        "image_generation_animation" to "图片生成动效",
        "omit_app_server_notification_media" to "通知省略媒体内容",
        "file_backed_tool_audio" to "工具音频使用文件",
        "image_resize_notice" to "图片尺寸调整提示",
        "unified_image_budget" to "统一图片预算",
        "safe_image_decoder" to "安全图片解码",
        "concurrent_reasoning_summaries" to "并行推理摘要",
        "output_token_replay" to "输出词元重放",
        "skill_mcp_dependency_install" to "技能 MCP 依赖安装",
        "omit_skill_descriptions" to "省略技能描述",
        "skill_search" to "技能搜索",
        "mentions_v2" to "第二代引用能力",
        "terminal_visualization_instructions" to "终端可视化说明",
        "guardian_approval" to "Guardian 安全审批",
        "guardian_include_additional_context" to "安全审批附加上下文",
        "guardian_recorded_items" to "安全审批记录项",
        "guardian_reuse_parent_compaction" to "安全审批复用父级压缩",
        "guardian_root_handoff_context" to "安全审批根任务交接上下文",
        "guardian_native_multi_agent_history" to "安全审批原生多智能体历史",
        "guardian_native_action_framing" to "安全审批原生动作框架",
        "guardian_nested_approval_context" to "嵌套审批上下文",
        "guardian_enhanced_node_repl_transcripts" to "增强 Node 执行记录",
        "guardian_node_repl_transcript_images" to "Node 执行记录图片",
        "guardian_trust_orchestrator_connectors" to "审批信任协调器连接器",
        "guardian_conversation_history_tools" to "审批会话历史工具",
        "guardian_denial_reconsideration" to "审批拒绝后重新评估",
        "guardianv2" to "第二代安全审批",
        "guardianv2_decisions_comparison" to "第二代审批决策对照",
        "guardianv3" to "第三代安全审批",
        "goals" to "任务目标",
        "rollout_budget" to "执行预算",
        "reasoning_effort_override" to "思考深度覆盖",
        "utc_environment_date" to "UTC 环境日期",
        "tool_call_mcp_elicitation" to "MCP 工具交互询问",
        "auth_elicitation" to "主动授权询问",
        "bedrock_setup_wizard" to "Bedrock 配置向导",
        "ultrafast_mode" to "超快模式",
        "step_model_switching" to "按步骤切换模型",
        "async_compaction_root_only" to "仅根会话异步压缩",
        "compaction_image_budget" to "压缩图片预算",
        "retain_client_developer_messages" to "保留客户端开发者消息",
        "retain_client_tool_outputs" to "保留客户端工具输出",
        "use_agent_identity" to "使用智能体身份",
        "runtime_agent_instances" to "运行时智能体实例",
        "workspace_dependencies" to "工作区依赖管理"
    )
    enum class UseGroup(val label: String) {
        Conversation("对话与模型"), Context("记忆与上下文"), Development("开发与执行"),
        Extensions("工具与扩展"), Safety("权限与安全"), Experience("界面与交互"),
        Advanced("高级与其他")
    }

    val statusFilters = listOf("all" to "全部状态", "preferred" to "安卓优先",
        "enabled" to "已开启", "disabled" to "已关闭", "stable" to "稳定",
        "experimental" to "实验与开发", "retired" to "历史功能")

    fun group(name: String): UseGroup = when {
        name.startsWith("guardian") || name.contains("approval") || name.contains("permission") ||
            name.contains("credential") || name.contains("sandbox") || name.contains("auth") -> UseGroup.Safety
        name.contains("memory") || name.contains("memories") || name.contains("context") ||
            name.contains("compact") || name.contains("history") || name.contains("token_budget") -> UseGroup.Context
        name.startsWith("code_mode") || name.contains("exec") || name.contains("shell") ||
            name.contains("workspace") || name.contains("terminal") || name.contains("environment") ||
            name.contains("file") || name.contains("dependencies") -> UseGroup.Development
        name.contains("plugin") || name.contains("mcp") || name.contains("tool") ||
            name.contains("apps") || name.contains("skill") || name.contains("connector") -> UseGroup.Extensions
        name.contains("model") || name.contains("reasoning") || name.contains("agent") ||
            name.contains("turn") || name.contains("thread") || name.contains("goal") -> UseGroup.Conversation
        name.startsWith("in_app") || name.startsWith("tui") || name.contains("announcement") ||
            name.contains("voice") || name.contains("display") || name.contains("ui_") -> UseGroup.Experience
        else -> UseGroup.Advanced
    }

    fun matches(row: CoreFeatureSwitch, filter: String): Boolean = when (filter) {
        "all" -> true
        "preferred" -> row.name in preferred
        "enabled" -> row.enabled
        "disabled" -> !row.enabled
        "stable" -> row.stage == "stable"
        "experimental" -> row.stage in setOf("beta", "underDevelopment")
        "retired" -> row.stage in setOf("deprecated", "removed")
        else -> error("未知功能状态筛选：$filter")
    }

    /** Only a known presentation-only stable toggle is one tap. All other changes keep confirmation. */
    fun needsConfirmation(row: CoreFeatureSwitch): Boolean =
        row.stage != "stable" || row.name != "suppress_model_change_announcements"

    fun parse(item: JSONObject): CoreFeatureSwitch {
        val key = item.getString("name")
        require(key.isNotBlank()) { "内核返回了空功能名称" }
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
        require(isWritableName(name))
        return JSONObject().put("edits", org.json.JSONArray().put(
            JSONObject().put("keyPath", "features.$name").put("value", enabled).put("mergeStrategy", "replace")
        )).put("reloadUserConfig", true).apply { version?.let { put("expectedVersion", it) } }
    }
}
