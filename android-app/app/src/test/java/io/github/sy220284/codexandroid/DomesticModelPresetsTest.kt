package io.github.sy220284.codexandroid

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DomesticModelPresetsTest {
    private val presets = DomesticModelPresets.parse(
        File(requireNotNull(System.getProperty("codex.domesticCatalog"))).readText()
    )

    @Test fun onlyMainlandEndpointsAndNativeResponses() {
        assertEquals(setOf("deepseek-cn", "glm-cn", "minimax-cn", "kimi-cn", "kimicode-cn", "qwen-cn"),
            presets.map { it.id }.toSet())
        assertEquals(setOf(
            "https://api.deepseek.com", "https://open.bigmodel.cn/api/v1",
            "https://api.minimax.cn/v1", "https://api.moonshot.cn/v1",
            "https://api.kimi.com/coding/v1", "https://dashscope.aliyuncs.com/compatible-mode/v1"
        ), presets.map { it.baseUrl }.toSet())
        assertEquals(setOf("deepseek-cn", "minimax-cn", "kimi-cn", "kimicode-cn"), presets.filter { it.catalog == "openai-list" }.map { it.id }.toSet())
        assertTrue(presets.all { it.protocol == "responses" && it.models.isNotEmpty() })
        assertEquals("codex-list", presets.first { it.id == "glm-cn" }.catalog)
        assertEquals(6, presets.first { it.id == "kimicode-cn" }.models.size)
        assertEquals(6, presets.map { it.envKey }.toSet().size)
    }

    @Test fun workspaceBindingRejectsOtherRegionsAndExtraLabels() {
        val preset = presets.first { it.id == "qwen-cn" }
        val endpoint = preset.workspaceEndpoint("llm-ab12")
        assertEquals("llm-ab12", preset.workspaceId(endpoint))
        assertTrue(preset.liveCatalog(endpoint))
        assertFalse(preset.liveCatalog(preset.baseUrl))
        assertFalse(preset.matchesEndpoint(preset.workspaceEndpoint("a.b")))
        assertFalse(preset.matchesEndpoint(preset.workspaceEndpoint("foo@bar")))
        assertFalse(preset.matchesEndpoint(endpoint.replace("cn-beijing", "cn-hongkong")))
        assertFalse(preset.matchesEndpoint(endpoint + "?x=1"))
    }

    @Test fun endpointMatchingRejectsHostAndPathSubstitution() {
        val preset = presets.first()
        assertTrue(preset.matchesEndpoint(preset.baseUrl + "/"))
        assertFalse(preset.matchesEndpoint(preset.baseUrl + ".evil.example"))
        assertFalse(preset.matchesEndpoint(preset.baseUrl + "/wrong"))
        assertFalse(preset.matchesEndpoint("http://api.deepseek.com"))
    }
}
