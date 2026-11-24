package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class V6InteractionPolicyTest {
    @Test fun shortcutOnlyMatchesSinglePrefixAndDoesNotTreatPathsAsCommands() {
        assertEquals("", ComposerShortcutPolicy.query("/"))
        assertEquals("mod", ComposerShortcutPolicy.query("/mod"))
        assertNull(ComposerShortcutPolicy.query("/usr/bin"))
        assertNull(ComposerShortcutPolicy.query("/model some text"))
        assertTrue(ComposerShortcutPolicy.matches("mod", "model", "选择模型"))
        assertTrue(ComposerShortcutPolicy.matches("模型", "model", "选择模型"))
        assertFalse(ComposerShortcutPolicy.matches("unknown", "model", "选择模型"))
    }

    @Test fun dailyUsageUsesCalendarRangeAndKeepsUnknownSeparateFromZero() {
        val rows = listOf(DailyUsagePolicy.sample("2026-11-01", 13L),
            DailyUsagePolicy.sample("2026-11-24", 0L),
            DailyUsagePolicy.sample("2026-11-23", null),
            DailyUsagePolicy.sample("unknown", 20L))
        val recent = DailyUsagePolicy.select(rows, 7)
        assertEquals(2, recent.size)
        assertNull(recent[0].tokens)
        assertEquals(0L, recent[1].tokens)
        assertEquals(4, DailyUsagePolicy.select(rows, null).size)
        assertEquals(3, DailyUsagePolicy.select(rows, 30).size)
    }
}
