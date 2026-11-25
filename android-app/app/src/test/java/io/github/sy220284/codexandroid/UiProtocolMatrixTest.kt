package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class UiProtocolMatrixTest {
    @Test fun everyProtocolMethodHasExactlyOneUiExposure() {
        assertEquals(198, UiProtocolMatrix.all.size)
        assertEquals(198, UiProtocolMatrix.byMethod.size)
        assertEquals(37, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Direct })
        assertEquals(47, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Contextual })
        assertEquals(53, UiProtocolMatrix.advanced.size)
        assertEquals(61, UiProtocolMatrix.systemOnly.size)
    }

    @Test fun newlyExposedConversationOperationsAreContextual() {
        listOf("modelProvider/model/verify", "thread/section/move", "threadSection/list", "threadSection/create",
            "threadSection/update", "threadSection/delete",
            "thread/realtime/appendText", "thread/realtime/appendSpeech").forEach { method ->
            assertEquals(method, ApiExposure.Contextual, UiProtocolMatrix.byMethod[method]?.exposure)
        }
    }

    @Test fun accountVoiceSettingsAreAccountScopedAdvancedActions() {
        listOf("account/voiceSettings/read", "account/voiceSettings/write").forEach { method ->
            val entry = UiProtocolMatrix.byMethod.getValue(method)
            assertEquals(ApiExposure.Advanced, entry.exposure)
            assertEquals("账号/额度", entry.owner)
            assertTrue(entry.experimental)
        }
    }

    @Test fun agentTransportProtocolsRemainInternal() {
        listOf("process/spawn", "process/writeStdin", "process/resizePty",
            "thread/realtime/appendAudio", "mcpServer/event/stream/start",
            "plugin/reconcile", "hooks/list", "mcpServer/tool/call",
            "mcpServer/resource/read", "mcpServer/oauth/login",
            "config/mcpServer/reload", "app/read").forEach { method ->
            assertEquals(method, ApiExposure.Internal, UiProtocolMatrix.byMethod[method]?.exposure)
        }
        assertFalse("后台钩子不应自动成为设置入口", "hooks" in UiFeatureCatalog.byRoute)
    }

    @Test fun newSurfacesAreUniqueAndKeepAllExistingRoutes() {
        assertEquals(31, UiFeatureCatalog.all.size)
        assertEquals(31, UiFeatureCatalog.byRoute.size)
        assertEquals(29, UiFeatureCatalog.legacyRoutes.size)
        assertEquals(5, UiFeatureCatalog.settingsGroups.size)
        assertTrue(UiFeatureCatalog.requiresThread("queue"))
        assertFalse(UiFeatureCatalog.requiresThread("plugins"))
        assertEquals(FeatureSurface.Page, UiFeatureCatalog.find("review").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("threads").surface)
        assertEquals(FeatureSurface.Page, UiFeatureCatalog.find("approvals").surface)
        assertEquals(FeatureSurface.Page, UiFeatureCatalog.find("queue").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("search").surface)
        assertEquals(FeatureSurface.Inline, UiFeatureCatalog.find("update").surface)
        assertEquals(FeatureSurface.Sheet, UiFeatureCatalog.find("modes").surface)
        assertTrue(UiFeatureCatalog.settingsGroups.flatMap { it.routes }.distinct().size ==
            UiFeatureCatalog.settingsGroups.sumOf { it.routes.size })
        assertFalse(UiFeatureCatalog.settingsGroups.any { group ->
            group.routes.any { it in listOf("goals", "queue", "review", "diff", "terminal", "search") }
        })
    }
}
