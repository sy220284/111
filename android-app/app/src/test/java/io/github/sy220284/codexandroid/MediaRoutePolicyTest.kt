package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaRoutePolicyTest {
    private fun model(id: String, vararg modalities: String) = JSONObject().put("id", id)
        .put("inputModalities", JSONArray(modalities.toList()))

    @Test fun unknownRemainsNativeAndKnownMismatchSuggestsOnlyCompleteVisibleModels() {
        val catalog = listOf(model("text", "text"), model("vision", "text", "image"),
            model("full", "text", "image", "audio"), model("hidden", "image", "audio").put("hidden", true),
            JSONObject().put("id", "unknown"))
        assertTrue(MediaRoutePolicy.decide(catalog, "unknown", setOf("image", "audio")).acceptsNative)
        val decision = MediaRoutePolicy.decide(catalog, "vision", setOf("image", "audio"))
        assertEquals(setOf("audio"), decision.missing)
        assertEquals(listOf("full"), decision.compatibleModels)
        assertTrue(MediaRoutePolicy.decide(catalog, "full", setOf("image", "audio")).acceptsNative)
    }
}
