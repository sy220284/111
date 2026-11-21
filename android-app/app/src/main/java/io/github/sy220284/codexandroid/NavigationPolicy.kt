package io.github.sy220284.codexandroid

/**
 * A user-task oriented navigation rule, independent from protocol plumbing.
 *
 * Daily actions remain one tap away. Specialist screens still have exact routes
 * and are reachable through explicit expansion or search, never deleted.
 * Backend RPC lifecycle methods have no navigation route.
 */
object NavigationPolicy {
    val primaryRoutes = listOf("home", "chat", "files", "threads")

    private val everydayRoutes = setOf(
        "home", "chat", "threads", "approvals",
        "files", "diff", "terminal", "review",
        "skills", "plugins", "apps", "mcp",
        "account", "usage", "security", "appearance"
    )

    fun visible(id: String, revealAdvanced: Boolean, searching: Boolean): Boolean =
        searching || revealAdvanced || id in everydayRoutes

    fun isMoreSelected(id: String): Boolean = id !in primaryRoutes

    fun matches(query: String, title: String, subtitle: String, id: String): Boolean {
        val q = query.trim()
        return q.isBlank() || listOf(title, subtitle, id).any {
            it.contains(q, ignoreCase = true)
        }
    }
}
