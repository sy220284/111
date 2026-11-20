package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class InspectionContractsTest {
    @Test fun mcpResourceUsesRequiredServerAndUri() {
        val request = InspectionContracts.mcpResource("local-tools", "resource://docs/readme")
        assertEquals("local-tools", request.getString("server"))
        assertEquals("resource://docs/readme", request.getString("uri"))
        assertEquals(2, request.length())
    }

    @Test fun pluginReadPrefersLocalPathOverMarketplaceName() {
        val request = InspectionContracts.plugin("formatter", "default", "/data/user/0/plugins")
        assertEquals("formatter", request.getString("pluginName"))
        assertEquals("/data/user/0/plugins", request.getString("marketplacePath"))
        assertFalse(request.has("remoteMarketplaceName"))
    }

    @Test fun pluginReadSupportsRemoteMarketplace() {
        val request = InspectionContracts.plugin("formatter", "official", "")
        assertEquals("official", request.getString("remoteMarketplaceName"))
        assertFalse(request.has("marketplacePath"))
    }

    @Test fun missingResourceOrPluginProvenanceFailsBeforeRpc() {
        for (run in listOf<() -> Unit>(
            { InspectionContracts.mcpResource("", "resource://docs") },
            { InspectionContracts.mcpResource("local", "") },
            { InspectionContracts.plugin("", "official", "") },
            { InspectionContracts.plugin("formatter", "", "") }
        )) {
            try {
                run()
                fail("Invalid read request must fail before reaching app-server")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
