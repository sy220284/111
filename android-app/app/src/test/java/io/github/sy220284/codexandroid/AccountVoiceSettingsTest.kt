package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountVoiceSettingsTest {
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
