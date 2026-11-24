package io.github.sy220284.codexandroid

/** 所有导航入口由 UI V5 唯一目录派生。 */
data class FeatureGroup(val title: String, val routes: List<String>)

object NavigationPolicy {
    /** 冷启动、恢复和新建任务统一使用唯一任务首页。 */
    const val startRoute = "chat"
    fun canonicalRoute(route: String): String = if (route in setOf("home", "security")) startRoute else route
    val sidebarRoutes get() = UiFeatureCatalog.sidebarRoutes
    val featureGroups get() = UiFeatureCatalog.settingsGroups
    val settingsRoutes get() = featureGroups.flatMap { it.routes }
    /**
     * The settings overview already links directly to account and connection health.
     * When filtering, expose their full routes as precise search results.
     */
    fun showInSettingsGroup(route: String, query: String): Boolean =
        query.isNotBlank() || route !in setOf("account", "diagnostics")
    /** 全局新建任务入口共享同一状态判断，允许在其他会话执行时并行创建。 */
    fun newThreadBlockReason(firstThreadStarting: Boolean, switching: Boolean): String? = when {
        firstThreadStarting -> "正在创建会话，完成后可新建任务"
        switching -> "正在切换会话，完成后可新建任务"
        else -> null
    }

    fun isCommon(id: String) = id == startRoute || id in sidebarRoutes
    /** Old saved stacks may still contain routes that are now shown as sheets. */
    fun pageHistory(routes: List<String>): List<String> = routes.map(::canonicalRoute).filter {
        UiFeatureCatalog.byRoute[it]?.surface in
            setOf(FeatureSurface.Page, FeatureSurface.Reader)
    }.fold(emptyList()) { history, route ->
        if (history.lastOrNull() == route) history else history + route
    }
    // 设置归属只用于设置项高亮；任务工具与工作区页面有独立归属。
    fun isSettingsChild(id: String) = id in settingsRoutes
    // 所有二级全屏页面保留返回按钮，返回实际来路而非误认为设置页。
    fun isDetailPage(id: String) = UiFeatureCatalog.byRoute[id]?.surface in
        setOf(FeatureSurface.Page, FeatureSurface.Reader) && !isCommon(id)
    /** 同一设置的常用叫法归到唯一功能入口；搜索索引不包含后台接口名。 */
    data class SettingsTopic(val route: String, val label: String, val aliases: List<String>)
    val settingsTopics = listOf(
        SettingsTopic("account", "账户登录与切换", listOf("登录", "退出", "账户切换", "绑定", "订阅")),
        SettingsTopic("providers", "API 密钥和模型服务商", listOf("API Key", "密钥", "模型渠道", "接口地址", "Base URL", "自定义服务商")),
        SettingsTopic("models", "默认模型与推理", listOf("模型选择", "模型列表", "模型目录", "思考模型", "思考深度", "回复详略")),
        SettingsTopic("usage", "用量与额度", listOf("使用量", "用量", "调用量", "消耗", "调用统计", "限额", "额度", "频率", "速率", "剩余额度")),
        SettingsTopic("skills", "技能启用和配置", listOf("技能开关", "技能管理", "skill")),
        SettingsTopic("plugins", "插件安装和管理", listOf("插件市场", "安装插件", "扩展商店")),
        SettingsTopic("apps", "连接应用与授权", listOf("应用授权", "连接应用", "连接器")),
        SettingsTopic("mcp", "MCP 工具服务", listOf("工具服务", "MCP服务器", "外部工具")),
        SettingsTopic("environments", "选择执行设备", listOf("远程主机", "执行环境", "工作主机", "远程执行服务", "本机执行", "连接其他设备", "后台运行", "终端等待", "会话释放", "运行参数")),
        SettingsTopic("config", "开发者高级配置", listOf("高级设置", "引擎配置", "配置键值")),
        SettingsTopic("personalization", "全局执行指令", listOf("自定义指令", "工作偏好", "个性化")),
        SettingsTopic("context", "上下文管理", listOf("上下文", "历史压缩", "自动压缩", "词元", "工具输出")),
        SettingsTopic("tools", "联网搜索", listOf("联网", "网络搜索", "搜索方式")),
        SettingsTopic("diagnostics", "连接诊断和日志", listOf("日志", "内核日志", "故障排查", "连接失败", "无法连接")),
        SettingsTopic("appearance", "字体主题与动效", listOf("字体", "字号", "主题", "深色", "夜间", "浅色", "动效", "减少动画", "外观与交互", "推理摘要")),
        SettingsTopic("about", "应用版本信息", listOf("应用版本", "版本号", "关于")),
        SettingsTopic("update", "检查与安装更新", listOf("更新", "升级", "安装包", "新版本"))
    )
    /** 搜索结果包含具体操作目标；入口和表单共用同一注册位置。 */
    data class SettingsSpot(val route: String, val target: String, val label: String, val terms: List<String>)
    val settingsSpots = listOf(
        SettingsSpot("appearance", "font", "文字大小", listOf("字号", "字体", "字体大小", "font size", "文字放大")),
        SettingsSpot("appearance", "compact", "紧凑布局", listOf("界面密度", "紧凑模式", "减少间距")),
        SettingsSpot("appearance", "shadow", "柔和阴影", listOf("阴影效果", "材质效果")),
        SettingsSpot("appearance", "motion", "减少动效", listOf("关闭动画", "减少动画", "动效开关")),
        SettingsSpot("appearance", "theme", "界面配色", listOf("主题", "深色", "浅色", "夜间", "配色")),
        SettingsSpot("plugins", "discover", "发现和安装插件", listOf("插件市场", "发现插件", "安装插件", "扩展商店")),
        SettingsSpot("environments", "remote", "连接其他设备", listOf("远程主机", "远程执行", "远程执行服务", "外部设备")),
        SettingsSpot("providers", "custom", "配置模型服务商", listOf("API Key", "Base URL", "接口地址", "自定义服务商", "密钥变量")),
        SettingsSpot("diagnostics", "logs", "查看诊断日志", listOf("日志", "内核日志", "启动日志", "故障排查")),
        SettingsSpot("config", "advanced", "高级自定义配置", listOf("高级配置", "自定义配置", "配置键值")),
        SettingsSpot("usage", "limits", "额度窗口", listOf("额度", "限额", "重置时间", "剩余额度")),
        SettingsSpot("update", "check", "检查应用更新", listOf("检查更新", "应用升级", "安装更新", "下载安装包"))
    ) + EngineSettings.all.map { setting ->
        SettingsSpot(setting.owner, setting.key, setting.title, when (setting.key) {
            "developer_instructions" -> listOf("全局执行指令", "自定义指令")
            "model_verbosity" -> listOf("回复详细程度", "回复详略")
            "web_search" -> listOf("联网搜索方式", "网络搜索方式")
            "hide_agent_reasoning" -> listOf("隐藏推理摘要", "推理摘要")
            "background_terminal_max_timeout" -> listOf("后台终端单次等待上限", "终端等待")
            else -> listOf(setting.title)
        })
    }
    fun matchedSettingSpot(id: String, query: String): SettingsSpot? {
        val q = query.trim()
        if (q.isBlank()) return null
        // 完整页面名称优先于操作词（如“诊断与日志”中的“日志”）。
        // 具体操作搜索仍可直达表单；更新入口不在 pages 列表中。
        val pageTitle = pages.firstOrNull { it.id == id }?.title
            ?: if (id == "update") "应用更新" else null
        if (pageTitle?.equals(q, ignoreCase = true) == true) return null
        val candidates = settingsSpots.filter { it.route == id }
        // Prefer the longest exact operation wording (e.g. plan effort vs general effort).
        candidates.filter { spot -> q.contains(spot.label, true) || spot.terms.any { q.contains(it, true) } }
            .maxByOrNull { spot -> (listOf(spot.label) + spot.terms).filter { q.contains(it, true) }.maxOf { it.length } }
            ?.let { return it }
        return candidates.filter { spot -> q.length >= 2 &&
            (listOf(spot.label) + spot.terms).any { it.contains(q, true) } }.singleOrNull()
    }

    fun matchedSettingTopic(id: String, query: String): String? {
        val q = query.trim()
        if (q.isBlank()) return null
        return settingsTopics.firstOrNull { topic ->
            topic.route == id && (topic.label.contains(q, ignoreCase = true) ||
                topic.aliases.any { alias -> alias.contains(q, ignoreCase = true) ||
                    (q.length >= 3 && q.contains(alias, ignoreCase = true)) })
        }?.label
    }
    fun matches(query: String, title: String, subtitle: String, id: String): Boolean {
        val q = query.trim()
        return q.isBlank() || listOf(title, subtitle, id).any { it.contains(q, ignoreCase = true) } ||
            (matchedSettingSpot(id, q) != null || matchedSettingTopic(id, q) != null)
    }
}

/** 一次性页面定位请求：导航成功后由所属页面消费，不跨会话保存。 */
internal object SettingsDeepLink {
    private var pending: Pair<String, String>? = null
    @Synchronized fun select(route: String, target: String?) {
        pending = if (target.isNullOrBlank()) null else route to target
    }
    @Synchronized fun take(route: String): String? {
        val found = pending ?: return null
        if (found.first != route) return null
        pending = null
        return found.second
    }
}
