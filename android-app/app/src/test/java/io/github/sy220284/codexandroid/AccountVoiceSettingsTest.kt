package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountVoiceSettingsTest {
    @Test fun voiceTransactionReservesAccountUntilReadbackCompletes() {
        val loading = kotlinx.coroutines.flow.MutableStateFlow(setOf("accountSessionSwitch"))
        assertFalse(AccountVoiceSettingsLease.acquire(loading))
        loading.value = setOf("account")
        assertFalse(AccountVoiceSettingsLease.acquire(loading))
        loading.value = setOf("models")
        assertTrue(AccountVoiceSettingsLease.acquire(loading))
        assertFalse(AccountVoiceSettingsLease.acquire(loading))
        for (method in listOf("account/login/start", "account/logout", "accountSession/switch")) {
            assertTrue(AccountVoiceSettingsLease.blocks(method, loading.value))
        }
        assertFalse(AccountVoiceSettingsLease.blocks("model/list", loading.value))
        AccountVoiceSettingsLease.release(loading)
        assertEquals(setOf("models"), loading.value)
        assertFalse(AccountVoiceSettingsLease.blocks("accountSession/switch", loading.value))
    }

    @Test fun accountVoiceUsesServerIdentifiersAndNames() {
        val snapshot = AccountVoiceSettingsSnapshot.parse(JSONObject(
            """{"selected":"cedar","voices":[{"voice":"cedar","name":"Cedar"},{"voice":"marin","name":"Marin"}]}"""))
        assertEquals("cedar", snapshot.selected)
        assertEquals("Marin", snapshot.voices[1].name)
        assertEquals("marin", snapshot.writeParams("marin").getString("voice"))
        assertFalse(RpcWireRequest.envelope("account/voiceSettings/read", 1, null).has("params"))
        assertEquals("marin", RpcWireRequest.envelope("account/voiceSettings/write", 2,
            snapshot.writeParams("marin")).getJSONObject("params").getString("voice"))
    }

    @Test fun rejectsUnlistedAndDuplicateVoiceIdentifiers() {
        val snapshot = AccountVoiceSettingsSnapshot.parse(JSONObject(
            """{"selected":"cedar","voices":[{"voice":"cedar","name":"Cedar"}]}"""))
        assertThrows(IllegalArgumentException::class.java) { snapshot.writeParams("unknown") }
        assertThrows(IllegalArgumentException::class.java) {
            AccountVoiceSettingsSnapshot.parse(JSONObject(
                """{"selected":"cedar","voices":[{"voice":"cedar","name":"A"},{"voice":"cedar","name":"B"}]}"""))
        }
    }
}
