package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveWorkspaceLayoutTest {
    @Test fun compactPhonesAndLandscapeUseOneWorkColumn() {
        assertFalse(AdaptiveWorkspaceLayout.persistentSidebar(393f, 851f))
        assertFalse(AdaptiveWorkspaceLayout.persistentSidebar(915f, 412f))
        assertFalse(AdaptiveWorkspaceLayout.persistentSidebar(800f, 900f))
        assertTrue(AdaptiveWorkspaceLayout.persistentSidebar(900f, 600f))
    }

    @Test fun goalPanelUsesAvailableSpaceWithoutCoveringTheWorkspace() {
        assertEquals(0.68f, AdaptiveWorkspaceLayout.goalSheetFraction(false, false), 0.0001f)
        assertEquals(0.82f, AdaptiveWorkspaceLayout.goalSheetFraction(true, false), 0.0001f)
        assertEquals(0.94f, AdaptiveWorkspaceLayout.goalSheetFraction(false, true), 0.0001f)
    }

    @Test fun shortQueuesDoNotOccupyTheWholeScreen() {
        assertEquals(0.62f, AdaptiveWorkspaceLayout.queueSheetFraction(0, false), 0.0001f)
        assertEquals(0.62f, AdaptiveWorkspaceLayout.queueSheetFraction(2, false), 0.0001f)
        assertEquals(0.78f, AdaptiveWorkspaceLayout.queueSheetFraction(5, false), 0.0001f)
        assertEquals(0.90f, AdaptiveWorkspaceLayout.queueSheetFraction(20, false), 0.0001f)
        assertEquals(0.94f, AdaptiveWorkspaceLayout.queueSheetFraction(0, true), 0.0001f)
    }
}
