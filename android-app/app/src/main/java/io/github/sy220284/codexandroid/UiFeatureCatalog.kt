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
        UiFeature("home", FeatureSurface.Page, FeatureOwner.Global, false),
        UiFeature("chat", FeatureSurface.Page, FeatureOwner.Thread, false),
        UiFeature("threads", FeatureSurface.Page, FeatureOwner.Thread, false),
        UiFeature("approvals", FeatureSurface.Page, FeatureOwner.Thread, false),
        UiFeature("search", FeatureSurface.Inline, FeatureOwner.Thread, true),
        UiFeature("goals", FeatureSurface.Sheet, FeatureOwner.Thread, false),
        UiFeature("queue", FeatureSurface.Page, FeatureOwner.Thread, true),
        UiFeature("files", FeatureSurface.Page, FeatureOwner.Workspace, false),
        UiFeature("diff", FeatureSurface.Reader, FeatureOwner.Thread, false),
        UiFeature("terminal", FeatureSurface.Page, FeatureOwner.Workspace, false),
        UiFeature("review", FeatureSurface.Sheet, FeatureOwner.Thread, false),
        UiFeature("models", FeatureSurface.Sheet, FeatureOwner.Account, false),
        UiFeature("modes", FeatureSurface.Sheet, FeatureOwner.Thread, true),
        UiFeature("skills", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("plugins", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("apps", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("mcp", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("hooks", FeatureSurface.Inline, FeatureOwner.System, false),
        UiFeature("environments", FeatureSurface.Page, FeatureOwner.System, true),
        UiFeature("account", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("providers", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("config", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("security", FeatureSurface.Page, FeatureOwner.System, false),
        UiFeature("usage", FeatureSurface.Page, FeatureOwner.Account, false),
        UiFeature("limits", FeatureSurface.Inline, FeatureOwner.Account, false),
        UiFeature("appearance", FeatureSurface.Inline, FeatureOwner.System, false),
        UiFeature("diagnostics", FeatureSurface.Page, FeatureOwner.System, true),
        UiFeature("about", FeatureSurface.Sheet, FeatureOwner.System, false),
        UiFeature("settings", FeatureSurface.Page, FeatureOwner.Global, false),
        UiFeature("update", FeatureSurface.Inline, FeatureOwner.System, false)
    )
    val byRoute = all.associateBy { it.route }
    val sidebarRoutes = listOf("home","chat","threads","files","approvals","settings")
    val bottomRoutes = listOf("home","chat","files","threads","settings")
    val workspaceTools = listOf("files","diff","terminal","review","goals","queue")
    val settingsGroups = listOf(
        FeatureGroup("账户与模型", listOf("account", "providers", "models")),
        FeatureGroup("扩展能力", listOf("skills", "plugins", "apps", "mcp", "hooks")),
        FeatureGroup("主机与安全", listOf("environments", "config", "security", "diagnostics")),
        FeatureGroup("通用与外观", listOf("appearance", "about", "update"))
    )
    val legacyRoutes = all.filter { it.route != "settings" && it.route != "update" }.map { it.route }
    val nonSettingsWorkRoutes = listOf("search","goals","queue","diff","terminal","review","modes","limits")
    fun requiresThread(route: String): Boolean = byRoute[route]?.needsThread == true
    fun find(route: String): UiFeature = requireNotNull(byRoute[route]) {
        "未注册的功能入口：$route"
    }
}
