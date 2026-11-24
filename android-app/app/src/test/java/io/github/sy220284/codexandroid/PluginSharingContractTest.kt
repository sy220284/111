package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginSharingContractTest {
    @Test fun shareCardCanBePastedIntoDownload() {
        val id = "plugin~demo_42"
        val url = "https://chatgpt.com/plugins/share/example"
        assertEquals(id, PluginSharingContract.remoteId(PluginSharingContract.shareDetails(id, url)))
        assertEquals(id, PluginSharingContract.remoteId("  $id  "))
        assertThrows(IllegalArgumentException::class.java) { PluginSharingContract.remoteId(url) }
    }

    @Test fun targetPayloadMatchesNativeFieldsAndRejectsDuplicateRoles() {
        val targets = PluginSharingContract.targets("  user:account-1:reader\ngroup:team-1:editor")
        assertEquals(2, targets.length())
        assertEquals("user", targets.getJSONObject(0).getString("principalType"))
        assertEquals("account-1", targets.getJSONObject(0).getString("principalId"))
        assertEquals("editor", targets.getJSONObject(1).getString("role"))
        assertThrows(IllegalArgumentException::class.java) {
            PluginSharingContract.targets("user:account-1:reader\nuser:account-1:editor")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginSharingContract.targets("workspace:team-1:editor")
        }
    }
}
