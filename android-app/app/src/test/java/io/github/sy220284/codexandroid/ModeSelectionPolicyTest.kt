package io.github.sy220284.codexandroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSelectionPolicyTest {
    @Test fun onlyConfirmedModesCanBeSelected() {
        assertTrue(ModeSelectionPolicy.maySelect("default", false, false, false))
        assertFalse(ModeSelectionPolicy.maySelect("plan", false, false, false))
        assertTrue(ModeSelectionPolicy.maySelect("plan", true, false, false))
        assertFalse(ModeSelectionPolicy.maySelect("__reload", true, false, false))
    }

    @Test fun runningOrRefreshingNeverActsLikeAnInteractiveOption() {
        assertFalse(ModeSelectionPolicy.maySelect("default", true, true, false))
        assertFalse(ModeSelectionPolicy.maySelect("plan", true, true, false))
        assertFalse(ModeSelectionPolicy.maySelect("default", true, false, true))
        assertFalse(ModeSelectionPolicy.maySelect("plan", true, false, true))
    }
}
