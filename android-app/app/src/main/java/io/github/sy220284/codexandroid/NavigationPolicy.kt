package io.github.sy220284.codexandroid

/** 日常入口与专业功能分开组织，所有旧页面仍保留独立路由。 */
data class FeatureGroup(val title: String, val routes: List<String>)

object NavigationPolicy {
    val sidebarRoutes = listOf("home", "chat", "threads", "files", "approvals", "settings")
    val primaryRoutes = listOf("home", "chat", "files", "threads", "settings")
    val quickToolRoutes = listOf("files", "diff", "terminal", "review", "goals", "queue")

    val featureGroups = listOf(
        FeatureGroup("账户与模型", listOf("account", "models", "providers", "modes", "usage", "limits")),
        FeatureGroup("扩展能力", listOf("skills", "plugins", "apps", "mcp", "hooks")),
        FeatureGroup("任务与开发", listOf("search", "goals", "queue", "diff", "terminal", "review")),
        FeatureGroup("主机与安全", listOf("environments", "config", "security", "diagnostics")),
        FeatureGroup("通用与外观", listOf("appearance", "about"))
    )

    val settingsRoutes: List<String> = featureGroups.flatMap { it.routes }

    fun isCommon(id: String): Boolean = id in sidebarRoutes
    fun isSettingsChild(id: String): Boolean = id in settingsRoutes

    fun matches(query: String, title: String, subtitle: String, id: String): Boolean {
        val q = query.trim()
        return q.isBlank() || listOf(title, subtitle, id).any {
            it.contains(q, ignoreCase = true)
        }
    }
}
