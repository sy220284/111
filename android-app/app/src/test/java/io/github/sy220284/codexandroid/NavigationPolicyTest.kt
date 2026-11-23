package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    @Test fun preciseSettingsSearchOpensActualControlContext() {
        val samples = mapOf(
            "字号" to ("appearance" to "font"),
            "深色" to ("appearance" to "theme"),
            "插件市场" to ("plugins" to "discover"),
            "远程主机" to ("environments" to "remote"),
            "API Key" to ("providers" to "custom"),
            "启动日志" to ("diagnostics" to "logs")
        )
        for ((query, expected) in samples) {
            val spot = NavigationPolicy.matchedSettingSpot(expected.first, query)
            assertNotNull("找不到具体设置项：$query", spot)
            assertEquals(expected.second, spot!!.target)
            SettingsDeepLink.select(spot.route, spot.target)
            assertNull(SettingsDeepLink.take("other"))
            assertEquals(spot.target, SettingsDeepLink.take(spot.route))
            assertNull(SettingsDeepLink.take(spot.route))
        }
        assertTrue(NavigationPolicy.settingsSpots.all { it.route in NavigationPolicy.settingsRoutes })
        // 搜索一级功能名称时应进入管理总览，只有明确的操作词才展开表单。
        assertNull(NavigationPolicy.matchedSettingSpot("providers", "模型服务商"))
        assertNull(NavigationPolicy.matchedSettingSpot("plugins", "插件"))
        assertNull(NavigationPolicy.matchedSettingSpot("appearance", "外观与交互"))
        assertEquals("custom", NavigationPolicy.matchedSettingSpot(
            "providers", "配置模型服务商")?.target)
    }

    @Test fun settingsOverviewAvoidsDuplicateEntriesWithoutHidingSearchResults() {
        assertFalse(NavigationPolicy.showInSettingsGroup("account", ""))
        assertFalse(NavigationPolicy.showInSettingsGroup("diagnostics", ""))
        assertTrue(NavigationPolicy.showInSettingsGroup("account", "登录"))
        assertTrue(NavigationPolicy.showInSettingsGroup("diagnostics", "日志"))
        assertTrue(NavigationPolicy.showInSettingsGroup("plugins", ""))
        assertTrue(NavigationPolicy.showInSettingsGroup("environments", ""))
    }

    @Test fun commonNavigationRemainsCompact() {
        assertEquals(listOf("chat", "home", "files", "settings"),
            NavigationPolicy.sidebarRoutes)
        assertFalse(NavigationPolicy.isCommon("approvals"))
        assertFalse(NavigationPolicy.isCommon("threads"))
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("approvals").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("threads").surface)
        assertFalse(NavigationPolicy.isCommon("review"))
        assertTrue(NavigationPolicy.isSettingsChild("plugins"))
        assertFalse(NavigationPolicy.isSettingsChild("terminal"))
        assertFalse(NavigationPolicy.isSettingsChild("review"))
        assertFalse(NavigationPolicy.isSettingsChild("threads"))
        assertFalse(NavigationPolicy.isDetailPage("threads"))
        assertFalse(NavigationPolicy.isDetailPage("approvals"))
        assertFalse(NavigationPolicy.isDetailPage("queue"))
        assertFalse(NavigationPolicy.isDetailPage("search"))
        assertTrue(NavigationPolicy.isDetailPage("terminal"))
        assertTrue(NavigationPolicy.isDetailPage("diff"))
        assertTrue(NavigationPolicy.isDetailPage("limits"))
        assertTrue(NavigationPolicy.isDetailPage("plugins"))
        assertFalse(NavigationPolicy.isDetailPage("home"))
        assertFalse(NavigationPolicy.isDetailPage("settings"))
        assertEquals(listOf("home", "chat", "limits"),
            NavigationPolicy.pageHistory(listOf("home", "threads", "chat", "queue", "limits")))
        assertEquals(emptyList<String>(),
            NavigationPolicy.pageHistory(listOf("search", "approvals", "about")))
    }

    @Test fun userRoutesRemainRegisteredAndSettingsExcludeWorkActions() {
        assertEquals(27, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(27, UiFeatureCatalog.legacyRoutes.distinct().size)
        assertEquals(4, NavigationPolicy.featureGroups.size)
        val settings = NavigationPolicy.settingsRoutes
        assertEquals(15, settings.size)
        assertEquals(settings.size, settings.distinct().size)
        assertTrue(settings.containsAll(listOf("models","usage","appearance","about","update")))
        assertFalse(settings.contains("limits")) // 兼容旧路由，但设置里只有一个用量与额度入口
        assertTrue(NavigationPolicy.matches("额度", "", "", "usage"))
        assertTrue(NavigationPolicy.matches("用量", "", "", "usage"))
        assertEquals(FeatureSurface.Page, UiFeatureCatalog.find("limits").surface)
        assertTrue(settings.none { it in listOf("goals","queue","review","search","diff","terminal", "hooks") })
        assertEquals(27, UiFeatureCatalog.legacyRoutes.count { it !in listOf("settings","update") })
    }

    @Test fun projectAndThreadToolsUseTheirOwnContext() {
        assertEquals(FeatureOwner.Workspace, UiFeatureCatalog.find("files").owner)
        assertEquals(FeatureOwner.Workspace, UiFeatureCatalog.find("terminal").owner)
        assertFalse(UiFeatureCatalog.requiresThread("files"))
        assertFalse(UiFeatureCatalog.requiresThread("terminal"))
        assertTrue(UiFeatureCatalog.requiresThread("review"))
        assertTrue(UiFeatureCatalog.requiresThread("queue"))
        assertTrue(UiFeatureCatalog.requiresThread("goals"))
        assertEquals(FeatureSurface.Reader, UiFeatureCatalog.find("diff").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("queue").surface)
        assertEquals(FeatureOwner.Global, UiFeatureCatalog.find("search").owner)
    }

    @Test fun settingsSearchResolvesCommonNamesToRegisteredDestinations() {
        val samples = mapOf(
            "API Key" to "providers", "免审批" to "security", "字号" to "appearance",
            "日志" to "diagnostics", "升级" to "update", "远程主机" to "environments",
            "插件市场" to "plugins", "MCP服务器" to "mcp"
        )
        for ((query, destination) in samples) {
            assertTrue("设置搜索没有命中：$query",
                NavigationPolicy.matches(query, "", "", destination))
            assertNotNull(NavigationPolicy.matchedSettingTopic(destination, query))
        }
        assertTrue(NavigationPolicy.settingsTopics.all {
            it.route in NavigationPolicy.settingsRoutes
        })
        assertEquals("用量与额度",
            NavigationPolicy.matchedSettingTopic("usage", "额度"))
        assertFalse(NavigationPolicy.settingsRoutes.contains("limits"))
        assertFalse(NavigationPolicy.matches("免审批", "项目管理", "任务工具", "queue"))
        assertNull(NavigationPolicy.matchedSettingTopic("queue", "免审批"))
    }

    @Test fun contextSearchMatchesExactNamesAndDescriptions() {
        assertTrue(NavigationPolicy.matches("审批", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("  权限 ", "权限与安全", "执行范围", "security"))
        assertFalse(NavigationPolicy.matches("文件", "账号登录", "会话", "account"))
    }
}
