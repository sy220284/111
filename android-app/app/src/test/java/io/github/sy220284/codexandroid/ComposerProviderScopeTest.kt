package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerProviderScopeTest {
    private val catalog = listOf(JSONObject().put("id", "deepseek-flash").put("isDefault", true))

    @Test fun composerOptionAccessMatchesRuntimeAndNextTurnRules() {
        for (hasThread in listOf(false, true))
            for (running in listOf(false, true))
                for (allowRunning in listOf(false, true)) {
                    assertEquals(!running || (hasThread && allowRunning),
                        ComposerOptionAccess.canChange(hasThread, running, allowRunning,
                            false, false, false, false))
                    for (blocked in 0..3) {
                        assertFalse(ComposerOptionAccess.canChange(hasThread, running, allowRunning,
                            blocked == 0, blocked == 1, blocked == 2, blocked == 3))
                    }
                }
    }

    @Test fun providerOwnsModelAndThread() {
        assertEquals("deepseek", ComposerProviderScope.resolve("deepseek", null, "openai"))
        assertEquals("deepseek", ComposerProviderScope.resolve(null, "deepseek", "openai"))
        assertEquals("openai", ComposerProviderScope.resolve(null, null, "openai"))
        assertThrows(IllegalArgumentException::class.java) {
            ComposerProviderScope.resolve("deepseek", "openai", "openai")
        }
        assertEquals("deepseek", ComposerProviderScope.request("deepseek").getString("modelProvider"))
        assertFalse(ComposerProviderScope.request("openai").has("forceRefresh"))
        assertTrue(ComposerProviderScope.request("openai", forceRefresh = true).getBoolean("forceRefresh"))
        assertFalse(ComposerProviderScope.request(forceRefresh = true).has("modelProvider"))
        assertNotEquals(ComposerProviderScope.catalogKey("deepseek"), ComposerProviderScope.catalogKey("openai"))
        assertEquals("deepseek", ComposerTaskOptions.decode(ComposerTaskOptions("deepseek-flash", modelProvider = "deepseek").encode()).modelProvider)
    }

    @Test fun imageAdmissionAllowsUnknownAndBlocksConfirmedTextOnlyWithoutDeletingDraft() {
        val unknown = JSONObject().put("id", "unknown")
        val image = JSONObject().put("id", "vision").put("inputModalities", org.json.JSONArray().put("text").put("image"))
        val text = JSONObject().put("id", "text").put("inputModalities", org.json.JSONArray().put("text"))
        val models = listOf(unknown, image, text)
        ComposerModelPolicy.requireImageInput(models, "unknown", true)
        ComposerModelPolicy.requireImageInput(models, "vision", true)
        ComposerModelPolicy.requireImageInput(models, "text", false)
        assertThrows(IllegalArgumentException::class.java) {
            ComposerModelPolicy.requireImageInput(models, "text", true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnRequestContract.build("thread", "读图", "text", "default", models, listOf("/image.png"))
        }
        val request = TurnRequestContract.build("thread", "读图", "vision", "default", models, listOf("/image.png"))
        assertEquals("localImage", request.getJSONArray("input").getJSONObject(1).getString("type"))
    }

    @Test fun defaultSelectionPreservesExplicitConfiguredModel() {
        assertEquals("deepseek-flash", ComposerProviderScope.initialModel(catalog, "", null, true))
        assertEquals("custom-alias", ComposerProviderScope.initialModel(catalog, "", "custom-alias", true))
        assertEquals("explicit", ComposerProviderScope.initialModel(emptyList(), "explicit", null, true))
        assertThrows(IllegalStateException::class.java) {
            ComposerProviderScope.initialModel(emptyList(), "", null, true)
        }
    }

    @Test fun modelSubmissionUsesSelectedProviderAndNativeInheritance() = kotlinx.coroutines.runBlocking {
        val allowed = JSONObject().put("id", "account-model").put("isDefault", true)
        val config = JSONObject().put("model_provider", "deepseek").put("model", "deepseek-flash")
        val methods = mutableListOf<String>()
        val rpc: suspend (String, JSONObject) -> JSONObject = { method, params ->
            methods += method
            when (method) {
                "model/list" -> {
                    assertEquals("openai", params.optString("modelProvider"))
                    JSONObject().put("data", org.json.JSONArray().put(allowed))
                }
                "thread/read" -> JSONObject().put("thread", JSONObject().put("modelProvider", "openai").put("model", "gpt-6.2-sol"))
                else -> error("unexpected RPC: $method")
            }
        }
        val first = ComposerProviderScope.submission(config, null, "", "default",
            ComposerTaskOptions(modelProvider = "openai"), rpc = rpc)
        assertEquals("account-model", first.model)
        assertEquals(listOf("model/list"), methods)
        methods.clear()
        val inherited = ComposerProviderScope.submission(config, "thread", "", "default", ComposerTaskOptions(), rpc = rpc)
        assertEquals("openai", inherited.provider)
        assertEquals("", inherited.model)
        assertEquals(listOf("thread/read"), methods)
        assertEquals("account-model", ComposerProviderScope.submission(
            config, "thread", "account-model", "default", ComposerTaskOptions(), rpc = rpc).model)
        methods.clear()
        val custom = ComposerProviderScope.submission(config, "thread", "custom-alias", "default", ComposerTaskOptions(), rpc = rpc)
        assertEquals("custom-alias", custom.model)
        assertEquals(listOf("thread/read"), methods)
        assertTrue(runCatching {
            ComposerProviderScope.submission(config, "thread", "deepseek-flash", "default",
                ComposerTaskOptions(modelProvider = "deepseek"), rpc = rpc)
        }.isFailure)
        methods.clear()
        val explicit = ComposerProviderScope.submission(config, null, "custom-alias", "default",
            ComposerTaskOptions(modelProvider = "openai"), rpc = rpc)
        assertEquals("custom-alias", explicit.model)
        assertTrue(methods.isEmpty())
        val plan = ComposerProviderScope.submission(config, "thread", "", "plan", ComposerTaskOptions(), rpc = rpc)
        assertEquals("gpt-6.2-sol", plan.effectiveConfig?.optString("model"))
        assertEquals(listOf("thread/read"), methods)
        assertTrue(ComposerProviderScope.isModelRejection(
            "The 'gpt-6.2-sol' model is not supported when using Codex with a ChatGPT account."))
        assertTrue(ComposerProviderScope.isModelRejection("model_not_in_accessible_catalog"))
        assertTrue(ComposerProviderScope.isModelRejection("模型不存在", "modelNotFound"))
        assertFalse(ComposerProviderScope.isModelRejection("Network timeout"))
    }

    @Test fun catalogDeduplicationPreservesProviderAndPageShape() {
        val scoped = ComposerProviderScope.request("openai", forceRefresh = true)
        val default = ModelCatalogQuery.from(ComposerProviderScope.request(), "openai")
        assertEquals(default, ModelCatalogQuery.from(scoped, "openai"))
        assertNotEquals(default, ModelCatalogQuery.from(ComposerProviderScope.request(), null))
        assertNotEquals(default, ModelCatalogQuery.from(ComposerProviderScope.request("deepseek"), "openai"))
        assertNotEquals(default, ModelCatalogQuery.from(scoped.put("includeHidden", true), "openai"))
        assertNotEquals(default, ModelCatalogQuery.from(ComposerProviderScope.request().put("cursor", "next"), "openai"))
        assertNotEquals(default, ModelCatalogQuery.from(ComposerProviderScope.request().put("limit", 1), "openai"))
    }

    @Test fun sameProviderSharesDefaultCatalogKey() {
        assertEquals("models", ComposerProviderScope.catalogKey("openai", "openai"))
        assertEquals("models", ComposerProviderScope.catalogKey(null, "openai"))
        assertEquals("composerModels:deepseek", ComposerProviderScope.catalogKey("deepseek", "openai"))
        assertEquals("composerModels:openai", ComposerProviderScope.catalogKey("openai"))
    }

    @Test fun capturedThreadProviderAvoidsTextPreflight() = kotlinx.coroutines.runBlocking {
        val config = JSONObject().put("model_provider", "deepseek")
        val rpc: suspend (String, JSONObject) -> JSONObject = { method, _ -> error("unexpected RPC: $method") }
        val inherited = ComposerProviderScope.submission(config, "t", "", "default",
            ComposerTaskOptions(), knownThreadProvider = "openai", rpc = rpc)
        assertEquals("openai", inherited.provider)
        assertEquals("", inherited.model)
        val explicit = ComposerProviderScope.submission(config, "t", "custom-alias", "plan",
            ComposerTaskOptions(modelProvider = "openai"), knownThreadProvider = "openai", rpc = rpc)
        assertEquals("custom-alias", explicit.model)
        assertTrue(runCatching {
            ComposerProviderScope.submission(config, "t", "custom", "default",
                ComposerTaskOptions(modelProvider = "deepseek"), knownThreadProvider = "openai", rpc = rpc)
        }.isFailure)
    }

    @Test fun modelEffortPreferenceRoundTripsAndRespectsCurrentCatalog() {
        val row = JSONObject().put("id", "a").put("supportedReasoningEfforts",
            org.json.JSONArray().put(JSONObject().put("reasoningEffort", "high")))
        val catalog = listOf(row, JSONObject().put("id", "b"))
        val legacy = ComposerTaskOptions("a", effort = "high", modelProvider = "provider")
        val legacyB = ComposerModelPolicy.choose(legacy, "provider", "b", catalog)
        assertEquals("high", ComposerModelPolicy.choose(legacyB, "provider", "a", catalog).effort)
        val a = ComposerModelPolicy.choose(ComposerTaskOptions(), "provider", "a", catalog)
        val remembered = ComposerModelPolicy.withEffort(a, "high")
        val b = ComposerModelPolicy.choose(remembered, "provider", "b", catalog)
        assertEquals("", b.effort)
        val restored = ComposerTaskOptions.decode(b.encode())
        assertEquals("high", ComposerModelPolicy.choose(restored, "provider", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "other", "a", catalog).effort)
        assertEquals("", ComposerModelPolicy.choose(restored, "provider", "a", listOf(JSONObject().put("id", "a"))).effort)
    }

    @Test fun recentOrderIsProviderScopedAndPersistsAcrossReopen() {
        val catalog = listOf(JSONObject().put("id", "a"), JSONObject().put("id", "b"))
        val a = ComposerModelPolicy.choose(ComposerTaskOptions(), "p", "a", catalog)
        val b = ComposerModelPolicy.choose(a, "p", "b", catalog)
        val restored = ComposerTaskOptions.decode(b.encode())
        assertEquals(0, ComposerModelPolicy.recentRank(restored, "p", "b"))
        assertEquals(1, ComposerModelPolicy.recentRank(restored, "p", "a"))
        assertEquals(Int.MAX_VALUE, ComposerModelPolicy.recentRank(restored, "other", "a"))
        val model = JSONObject().put("inputModalities", org.json.JSONArray().put("text").put("image"))
        assertEquals(listOf("text", "image"), ComposerModelPolicy.inputKinds(model))
        assertTrue(ComposerModelPolicy.inputKinds(JSONObject()).isEmpty())
    }

    @Test fun audioAndVideoUseRealNativeInputsWithoutInventingVideoProtocol() {
        val audio = TurnRequestContract.input("听录音", listOf("/sample.m4a"))
        assertEquals("localAudio", audio.getJSONObject(1).getString("type"))
        assertEquals(setOf("audio"), LocalMediaAttachmentPolicy.modalities(listOf("/sample.wav")))
        val dir = java.nio.file.Files.createTempDirectory("video-attachment-test").toFile()
        try {
            val video = java.io.File(dir, "sample.av.mp4").path
            createVideoFixture(video, true)
            val input = TurnRequestContract.input("", listOf(video))
            assertEquals("text", input.getJSONObject(0).getString("type"))
            assertEquals("text", input.getJSONObject(1).getString("type"))
            assertEquals("localImage", input.getJSONObject(2).getString("type"))
            assertEquals("localAudio", input.getJSONObject(3).getString("type"))
            assertEquals(setOf("image", "audio"), LocalMediaAttachmentPolicy.modalities(listOf(video)))
            val onlyImage = JSONObject().put("id", "vision").put("inputModalities", org.json.JSONArray().put("text").put("image"))
            assertThrows(IllegalArgumentException::class.java) {
                TurnRequestContract.build("t", "分析", "vision", "default", listOf(onlyImage), listOf(video))
            }
            java.io.File(VideoTimeline.audio(video, 0)).delete()
            assertThrows(IllegalArgumentException::class.java) { TurnRequestContract.input("", listOf(video)) }
            val silent = java.io.File(dir, "sample.silent.mp4").path
            createVideoFixture(silent, false)
            assertEquals(setOf("image"), LocalMediaAttachmentPolicy.modalities(listOf(silent)))
            assertEquals(3, TurnRequestContract.input("", listOf(silent)).length())
        } finally { dir.deleteRecursively() }
    }
}
