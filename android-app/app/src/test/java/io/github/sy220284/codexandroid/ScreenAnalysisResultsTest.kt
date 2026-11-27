package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ScreenAnalysisResultsTest {
    private fun observation(index: Int) = ScreenAnalysisResults.Observation(
        index * 1000L, index * 1000L + 500, "结果$index")

    @Test fun allTwelveResultsRemainAvailableInChronologicalOrder() {
        val results = (1..12).fold(ScreenAnalysisResults()) { state, index -> state.append(observation(index)) }
        assertEquals((1..12).map(::observation), results.observations)
        assertEquals(results.observations, results.pending)
        assertThrows(IllegalArgumentException::class.java) { results.append(observation(13)) }
    }

    @Test fun applyingAReviewedSnapshotDoesNotConsumeLaterResultsOrDuplicateOldOnes() {
        val reviewed = ScreenAnalysisResults().append(observation(1)).append(observation(2))
        val latest = reviewed.append(observation(3))
        val applied = latest.appliedThrough(reviewed.observations.size)
        assertEquals(listOf(observation(3)), applied.pending)
        assertEquals(3, applied.observations.size)
        val fullyApplied = applied.appliedThrough(3)
        assertTrue(fullyApplied.pending.isEmpty())
        assertEquals(fullyApplied, fullyApplied.appliedThrough(3))
        assertEquals(listOf(observation(4)), fullyApplied.append(observation(4)).pending)
        assertThrows(IllegalArgumentException::class.java) { fullyApplied.appliedThrough(2) }
        assertThrows(IllegalArgumentException::class.java) { fullyApplied.appliedThrough(4) }
    }
}
