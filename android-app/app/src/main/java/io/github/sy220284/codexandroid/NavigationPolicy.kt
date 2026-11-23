package io.github.sy220284.codexandroid

/** 所有导航入口由 UI V5 唯一目录派生。 */
data class FeatureGroup(val title: String, val routes: List<String>)

object NavigationPolicy {
    val sidebarRoutes get() = UiFeatureCatalog.sidebarRoutes
    val featureGroups get() = UiFeatureCatalog.settingsGroups
    val settingsRoutes get() = featureGroups.flatMap { it.routes }
    /**
     * The settings overview already links directly to account and connection health.
     * When filtering, expose their full routes as precise search results.
     */
    fun showInSettingsGroup(route: String, query: String): Boolean =
        query.isNotBlank() || route !in setOf("account", "diagnostics")
    fun isCommon(id: String) = id in sidebarRoutes
    /** Old saved stacks may still contain routes that are now shown as sheets. */
    fun pageHistory(routes: List<String>): List<String> = routes.filter {
        UiFeatureCatalog.byRoute[it]?.surface in
            setOf(FeatureSurface.Page, FeatureSurface.Reader)
    }
    // 设置归属只用于设置项高亮；任务工具与工作区页面有独立归属。
    fun isSettingsChild(id: String) = id in settingsRoutes
    // 所有二级全屏页面保留返回按钮，返回实际来路而非误认为设置页。
    fun isDetailPage(id: String) = UiFeatureCatalog.byRoute[id]?.surface in
        setOf(FeatureSurface.Page, FeatureSurface.Reader) && id !in sidebarRoutes
    /** 同一设置的常用叫法归到唯一功能入口；搜索索引不包含后台接口名。 */
    data class SettingsTopic(val route: String, val label: String, val aliases: List<String>)
    val settingsTopics = listOf(
        SettingsTopic("account", "账户登录与切换", listOf("登录", "退出", "账户切换", "绑定", "订阅")),
        SettingsTopic("providers", "API 密钥和模型服务商", listOf("API Key", "密钥", "模型渠道", "接口地址", "Base URL", "自定义服务商")),
        SettingsTopic("models", "运行模型选择", listOf("模型选择", "模型列表", "思考模型")),
        SettingsTopic("usage", "账户用量与额度", listOf("使用量", "用量", "调用量", "消耗", "调用统计", "额度", "限额", "速率", "重置时间")),
        SettingsTopic("skills", "技能启用和配置", listOf("技能开关", "技能管理", "skill")),
        SettingsTopic("plugins", "插件安装和管理", listOf("插件市场", "安装插件", "扩展商店")),
        SettingsTopic("apps", "连接应用与授权", listOf("应用授权", "连接应用", "连接器")),
        SettingsTopic("mcp", "MCP 工具服务", listOf("工具服务", "MCP服务器", "外部工具")),
        SettingsTopic("environments", "选择执行设备", listOf("远程主机", "执行环境", "工作主机", "远程执行服务", "本机执行", "连接其他设备")),
        SettingsTopic("config", "运行核心高级配置", listOf("高级设置", "运行参数", "环境变量")),
        SettingsTopic("security", "执行权限与确认", listOf("免审批", "审批模式", "授权", "沙箱", "权限方案", "危险操作")),
        SettingsTopic("diagnostics", "连接诊断和日志", listOf("日志", "内核日志", "故障排查", "连接失败", "无法连接")),
        SettingsTopic("appearance", "字体主题与动效", listOf("字体", "字号", "主题", "深色", "夜间", "浅色", "动效", "减少动画")),
        SettingsTopic("about", "应用版本信息", listOf("应用版本", "版本号", "关于")),
        SettingsTopic("update", "检查与安装更新", listOf("更新", "升级", "安装包", "新版本"))
    )
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
            matchedSettingTopic(id, q) != null
    }
}
