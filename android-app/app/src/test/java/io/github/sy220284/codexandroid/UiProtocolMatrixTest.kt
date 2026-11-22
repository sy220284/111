package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class UiProtocolMatrixTest {
    @Test fun everyProtocolMethodHasExactlyOneUiExposure() {
        assertEquals(195, UiProtocolMatrix.all.size)
        assertEquals(195, UiProtocolMatrix.byMethod.size)
        assertEquals(39, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Direct })
        assertEquals(43, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Contextual })
        assertEquals(52, UiProtocolMatrix.advanced.size)
        assertEquals(61, UiProtocolMatrix.systemOnly.size)
    }

    @Test fun agentTransportProtocolsRemainInternal() {
        listOf("process/spawn", "process/writeStdin", "process/resizePty",
            "thread/realtime/appendAudio", "mcpServer/event/stream/start",
            "plugin/reconcile", "hooks/list", "mcpServer/tool/call",
            "mcpServer/resource/read").forEach { method ->
            assertEquals(method, ApiExposure.Internal, UiProtocolMatrix.byMethod[method]?.exposure)
        }
        assertFalse("后台钩子不应自动成为设置入口", "hooks" in UiFeatureCatalog.byRoute)
    }

    @Test fun newSurfacesAreUniqueAndKeepAllExistingRoutes() {
        assertEquals(29, UiFeatureCatalog.all.size)
        assertEquals(29, UiFeatureCatalog.byRoute.size)
        assertEquals(27, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(4, UiFeatureCatalog.settingsGroups.size)
        assertTrue(UiFeatureCatalog.requiresThread("queue"))
        assertFalse(UiFeatureCatalog.requiresThread("plugins"))
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("review").surface)
        assertEquals(FeatureSurface.Inline, UiFeatureCatalog.find("update").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("modes").surface)
        assertTrue(UiFeatureCatalog.settingsGroups.flatMap { it.routes }.distinct().size ==
            UiFeatureCatalog.settingsGroups.sumOf { it.routes.size })
        assertFalse(UiFeatureCatalog.settingsGroups.any { group ->
            group.routes.any { it in listOf("goals", "queue", "review", "diff", "terminal", "search") }
        })
    }
}
