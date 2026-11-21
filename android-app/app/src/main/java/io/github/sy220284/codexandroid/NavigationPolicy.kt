package io.github.sy220284.codexandroid

/** 所有导航入口由 UI V5 唯一目录派生。 */
data class FeatureGroup(val title: String, val routes: List<String>)

object NavigationPolicy {
    val sidebarRoutes get() = UiFeatureCatalog.sidebarRoutes
    val primaryRoutes get() = UiFeatureCatalog.bottomRoutes
    val quickToolRoutes get() = UiFeatureCatalog.workspaceTools
    val featureGroups get() = UiFeatureCatalog.settingsGroups
    val settingsRoutes get() = featureGroups.flatMap { it.routes }
    fun isCommon(id: String) = id in sidebarRoutes
    fun isSettingsChild(id: String) = id !in sidebarRoutes &&
        id in UiFeatureCatalog.byRoute
    fun matches(query: String, title: String, subtitle: String, id: String): Boolean {
        val q = query.trim()
        return q.isBlank() || listOf(title, subtitle, id).any { it.contains(q, ignoreCase = true) }
    }
}
