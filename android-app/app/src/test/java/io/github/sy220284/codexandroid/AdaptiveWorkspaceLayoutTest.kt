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

    @Test fun shortQueuesDoNotOccupyTheWholeScreen() {
        assertEquals(0.62f, AdaptiveWorkspaceLayout.queueSheetFraction(0, false))
        assertEquals(0.62f, AdaptiveWorkspaceLayout.queueSheetFraction(2, false))
        assertEquals(0.78f, AdaptiveWorkspaceLayout.queueSheetFraction(5, false))
        assertEquals(0.90f, AdaptiveWorkspaceLayout.queueSheetFraction(20, false))
        assertEquals(0.94f, AdaptiveWorkspaceLayout.queueSheetFraction(0, true))
    }
}
