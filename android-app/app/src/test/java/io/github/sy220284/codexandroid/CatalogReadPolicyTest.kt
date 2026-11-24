package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogReadPolicyTest {
    @Test fun firstLoadAndConnectionAreDistinct() {
        assertEquals(CatalogReadStage.FirstLoad, CatalogReadPolicy.stage(false, false, true, true, false))
        assertEquals(CatalogReadStage.Connecting, CatalogReadPolicy.stage(false, true, false, false, false))
        assertEquals(CatalogReadStage.Disconnected, CatalogReadPolicy.stage(false, false, false, false, true))
        assertEquals(CatalogReadStage.Failed, CatalogReadPolicy.stage(false, false, true, false, true))
        assertEquals(CatalogReadStage.Unread, CatalogReadPolicy.stage(false, false, true, false, false))
    }

    @Test fun existingDataRemainsVisibleDuringRefreshAndFailure() {
        assertEquals(CatalogReadStage.Refreshing, CatalogReadPolicy.stage(true, false, true, true, false))
        assertEquals(CatalogReadStage.Stale, CatalogReadPolicy.stage(true, false, false, false, true))
        assertEquals(CatalogReadStage.Stale, CatalogReadPolicy.stage(true, true, false, true, false))
        assertEquals(CatalogReadStage.Ready, CatalogReadPolicy.stage(true, false, true, false, true))
        assertEquals(CatalogReadStage.Ready, CatalogReadPolicy.stage(true, false, true, false, false))
    }
}
