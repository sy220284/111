package io.github.sy220284.codexandroid

/** UI V5：以功能归属决定容器类型，不让业务动作自动占据设置路由。 */
enum class FeatureSurface { Page, Sheet, Dialog, Inline, Action, Reader }
enum class FeatureOwner { Global, Thread, Workspace, Account, System }
data class UiFeature(
    val route: String,
    val surface: FeatureSurface,
    val owner: FeatureOwner,
    val experimental: Boolean = false
) {
    val needsThread: Boolean get() = owner == FeatureOwner.Thread &&
        route in setOf("goals", "queue", "review", "diff")
}
object UiFeatureCatalog {
    val all = listOf(
        UiFeature("chat", FeatureSurface.Page, FeatureOwner.Thread, false),
        UiFeature("threads", FeatureSurface.Sheet, FeatureOwner.Global, false),
        UiFeature("approvals", FeatureSurface.Sheet, FeatureOwner.Global, false),
        UiFeature("search", FeatureSurface.Sheet, FeatureOwner.Global, false),
        UiFeature("goals", FeatureSurface.Sheet, FeatureOwner.Thread, false),
        UiFeature("queue", FeatureSurface.Sheet, FeatureOwner.Thread, true),
        UiFeature("files", FeatureSurface.Page, FeatureOwner.Workspace, false),
        UiFeature("diff", FeatureSurface.Reader, FeatureOwner.Thread, false),
        UiFeature("terminal", FeatureSurface.Page, FeatureOwner.Workspace, false),
        UiFeature("review", FeatureSurface.Sheet, FeatureOwner.Thread, false),
        UiFeature("models", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("modes", FeatureSurface.Sheet, FeatureOwner.Thread, true),
        UiFeature("skills", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("plugins", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("apps", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("mcp", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("environments", FeatureSurface.Page, FeatureOwner.System, true),
        UiFeature("account", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("providers", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("config", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("personalization", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("context", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("tools", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("security", FeatureSurface.Inline, FeatureOwner.Thread, false),
        UiFeature("usage", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("limits", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("appearance", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("diagnostics", FeatureSurface.Page, FeatureOwner.System, true),
        UiFeature("about", FeatureSurface.Sheet, FeatureOwner.System, false),
        UiFeature("settings", FeatureSurface.Page, FeatureOwner.Global, false),
        UiFeature("update", FeatureSurface.Inline, FeatureOwner.System, false)
    )
    val byRoute = all.associateBy { it.route }
    val sidebarRoutes = listOf("files","settings")
    /** 以用户的操作意图分组，设置项只有一个固定归属。 */
    val settingsGroups = listOf(
        FeatureGroup("账户与模型", listOf("account", "providers", "models", "usage")),
        FeatureGroup("个性化与显示", listOf("personalization", "appearance")),
        FeatureGroup("工具与能力", listOf("tools", "skills", "plugins", "apps", "mcp")),
        FeatureGroup("运行环境", listOf("context", "environments")),
        FeatureGroup("开发者与帮助", listOf("config", "diagnostics", "about", "update"))
    )
    val legacyRoutes = all.filter { it.route != "settings" && it.route != "update" }.map { it.route }
    val nonSettingsWorkRoutes = listOf("search","goals","queue","diff","terminal","review","modes")
    fun requiresThread(route: String): Boolean = byRoute[route]?.needsThread == true
    fun isSheet(route: String): Boolean = byRoute[route]?.surface == FeatureSurface.Sheet
    fun find(route: String): UiFeature = requireNotNull(byRoute[route]) {
        "未注册的功能入口：$route"
    }
}
