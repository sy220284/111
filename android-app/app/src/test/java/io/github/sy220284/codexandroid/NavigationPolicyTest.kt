package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val originalRoutes = listOf(
        "home", "chat", "threads", "search", "goals", "queue",
        "files", "diff", "terminal", "review",
        "models", "modes", "skills", "plugins", "apps", "mcp", "hooks", "environments",
        "account", "providers", "config", "security", "approvals", "usage",
        "limits", "appearance", "diagnostics", "about"
    )

    @Test fun compactNavigationContainsOnlyEverydayRoutes() {
        assertEquals(listOf("home", "chat", "threads", "files", "approvals", "settings"),
            NavigationPolicy.sidebarRoutes)
        assertEquals(listOf("home", "chat", "files", "threads", "settings"),
            NavigationPolicy.primaryRoutes)
        assertFalse(NavigationPolicy.isCommon("terminal"))
        assertTrue(NavigationPolicy.isCommon("approvals"))
    }

    @Test fun settingsGroupsCoverAllNonCommonFeaturesExactlyOnce() {
        assertEquals(23, NavigationPolicy.settingsRoutes.size)
        assertEquals(23, NavigationPolicy.settingsRoutes.distinct().size)
        val original = NavigationPolicy.sidebarRoutes.filter { it != "settings" } +
            NavigationPolicy.settingsRoutes
        assertEquals(28, original.size)
        assertEquals(originalRoutes.toSet(), original.toSet())
    }

    @Test fun quickToolsExposeOnlyExistingFeatureRoutes() {
        assertEquals(listOf("files", "diff", "terminal", "review", "goals", "queue"),
            NavigationPolicy.quickToolRoutes)
        assertTrue(NavigationPolicy.quickToolRoutes.all { it in originalRoutes })
    }

    @Test fun searchMatchesTitlesDescriptionsAndRouteIds() {
        assertTrue(NavigationPolicy.matches("审批", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("approvals", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("  权限 ", "权限与安全", "执行范围", "security"))
        assertFalse(NavigationPolicy.matches("文件", "账号登录", "会话", "account"))
    }
}
