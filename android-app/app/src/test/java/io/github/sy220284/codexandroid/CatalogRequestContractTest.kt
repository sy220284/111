package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class CatalogRequestContractTest {
    @Test fun catalogWarmupNeverClearsCachedOrInFlightResults() {
        assertTrue(CatalogRequestContract.shouldLoadCatalog(
            "providers", emptySet(), emptySet()))
        assertFalse(CatalogRequestContract.shouldLoadCatalog(
            "providers", setOf("providers"), emptySet()))
        assertFalse(CatalogRequestContract.shouldLoadCatalog(
            "providers", emptySet(), setOf("providers")))
        assertTrue(CatalogRequestContract.shouldLoadCatalog(
            "skills", setOf("providers"), setOf("models")))
    }

    @Test fun workspaceReadsAndRetriesKeepWorkspace() {
        val request = CatalogRequestContract.workspace("/work/project")
        assertEquals("/work/project", request.getJSONArray("cwds").getString(0))
        assertEquals(1, request.getJSONArray("cwds").length())
    }

    @Test fun mcpRefreshRetainsThreadAndToolDetail() {
        val request = CatalogRequestContract.mcpStatus("active-thread")
        assertEquals("full", request.getString("detail"))
        assertEquals("active-thread", request.getString("threadId"))
    }

    @Test fun accountSessionRefreshKeepsInitialReadFlags() {
        assertFalse(CatalogRequestContract.accountSessions().getBoolean("refreshWorkspaceMetadata"))
    }

    @Test fun mcpRefreshWithoutThreadKeepsValidServerQuery() {
        val request = CatalogRequestContract.mcpStatus(null)
        assertEquals("full", request.getString("detail"))
        assertFalse(request.has("threadId"))
    }
}
