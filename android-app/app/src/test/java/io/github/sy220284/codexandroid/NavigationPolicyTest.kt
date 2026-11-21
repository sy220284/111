package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val allRoutes = listOf(
        "home", "chat", "threads", "search", "goals", "queue",
        "files", "diff", "terminal", "review",
        "models", "modes", "skills", "plugins", "apps", "mcp", "hooks", "environments",
        "account", "providers", "config", "security", "approvals", "usage",
        "limits", "appearance", "diagnostics", "about"
    )

    @Test fun primaryTabsFollowDailyUseOrder() {
        assertEquals(listOf("home", "chat", "files", "threads"), NavigationPolicy.primaryRoutes)
        assertFalse("approvals" in NavigationPolicy.primaryRoutes)
        assertTrue(NavigationPolicy.isMoreSelected("approvals"))
        assertFalse(NavigationPolicy.isMoreSelected("chat"))
    }

    @Test fun initialMenuKeepsFrequentActionsAndHidesExpertPages() {
        val frequent = allRoutes.filter { NavigationPolicy.visible(it, revealAdvanced = false, searching = false) }
        assertTrue(frequent.containsAll(listOf(
            "home", "chat", "threads", "files", "approvals", "diff", "review",
            "skills", "plugins", "apps", "mcp", "account", "usage", "security", "appearance"
        )))
        assertFalse("queue" in frequent)
        assertFalse("diagnostics" in frequent)
        assertFalse("hooks" in frequent)
        assertFalse("config" in frequent)
        assertTrue(frequent.size < allRoutes.size)
    }

    @Test fun searchAndExplicitExpansionAlwaysReachAllTwentyEightScreens() {
        assertEquals(allRoutes, allRoutes.filter {
            NavigationPolicy.visible(it, revealAdvanced = true, searching = false)
        })
        assertEquals(allRoutes, allRoutes.filter {
            NavigationPolicy.visible(it, revealAdvanced = false, searching = true)
        })
        assertEquals(allRoutes.size, allRoutes.distinct().size)
    }

    @Test fun queryMatchesTitleSubtitleAndTechnicalRouteId() {
        assertTrue(NavigationPolicy.matches("审批", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("approvals", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("  权限 ", "权限与安全", "执行范围", "security"))
        assertFalse(NavigationPolicy.matches("文件", "账号登录", "会话", "account"))
    }
}
