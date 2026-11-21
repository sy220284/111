package io.github.sy220284.codexandroid

/** 所有导航入口由 UI V5 唯一目录派生。 */
data class FeatureGroup(val title: String, val routes: List<String>)

object NavigationPolicy {
    val sidebarRoutes get() = UiFeatureCatalog.sidebarRoutes
    val quickToolRoutes get() = UiFeatureCatalog.workspaceTools
    val featureGroups get() = UiFeatureCatalog.settingsGroups
    val settingsRoutes get() = featureGroups.flatMap { it.routes }
    fun isCommon(id: String) = id in sidebarRoutes
    // 设置归属只用于设置项高亮；任务工具与工作区页面有独立归属。
    fun isSettingsChild(id: String) = id in settingsRoutes
    // 所有二级全屏页面保留返回按钮，返回实际来路而非误认为设置页。
    fun isDetailPage(id: String) = id in UiFeatureCatalog.byRoute && id !in sidebarRoutes
    fun matches(query: String, title: String, subtitle: String, id: String): Boolean {
        val q = query.trim()
        return q.isBlank() || listOf(title, subtitle, id).any { it.contains(q, ignoreCase = true) }
    }
}
