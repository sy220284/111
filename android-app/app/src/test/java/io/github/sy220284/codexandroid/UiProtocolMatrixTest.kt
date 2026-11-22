package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class UiProtocolMatrixTest {
    @Test fun everyProtocolMethodHasExactlyOneUiExposure() {
        assertEquals(195, UiProtocolMatrix.all.size)
        assertEquals(195, UiProtocolMatrix.byMethod.size)
        assertEquals(40, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Direct })
        assertEquals(44, UiProtocolMatrix.all.count { it.exposure == ApiExposure.Contextual })
        assertEquals(71, UiProtocolMatrix.advanced.size)
        assertEquals(40, UiProtocolMatrix.systemOnly.size)
    }

    @Test fun newSurfacesAreUniqueAndKeepAllExistingRoutes() {
        assertEquals(30, UiFeatureCatalog.all.size)
        assertEquals(30, UiFeatureCatalog.byRoute.size)
        assertEquals(28, UiFeatureCatalog.legacyRoutes.size)
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
