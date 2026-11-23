package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class NavigationPolicyTest {
    @Test fun commonNavigationRemainsCompact() {
        assertEquals(listOf("home", "chat", "files", "settings"),
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
        assertEquals(14, settings.size)
        assertEquals(settings.size, settings.distinct().size)
        assertTrue(settings.containsAll(listOf("models","appearance","about","update")))
        assertTrue(settings.none { it in listOf("goals","queue","review","search","diff","terminal", "hooks") })
        assertEquals(27, UiFeatureCatalog.legacyRoutes.count { it !in listOf("settings","update") })
    }

    @Test fun quickToolsAreTaskScopedAndAllTargetsExist() {
        assertEquals(listOf("files","diff","terminal","review","goals","queue"),
            NavigationPolicy.quickToolRoutes)
        assertTrue(NavigationPolicy.quickToolRoutes.all { it in UiFeatureCatalog.byRoute })
        assertTrue(UiFeatureCatalog.requiresThread("review"))
        assertTrue(UiFeatureCatalog.requiresThread("queue"))
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("queue").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("search").surface)
        assertEquals(FeatureOwner.Global, UiFeatureCatalog.find("search").owner)
        assertFalse(UiFeatureCatalog.requiresThread("terminal"))
    }

    @Test fun contextSearchMatchesExactNamesAndDescriptions() {
        assertTrue(NavigationPolicy.matches("审批", "待审批操作", "审批详情", "approvals"))
        assertTrue(NavigationPolicy.matches("  权限 ", "权限与安全", "执行范围", "security"))
        assertFalse(NavigationPolicy.matches("文件", "账号登录", "会话", "account"))
    }
}
