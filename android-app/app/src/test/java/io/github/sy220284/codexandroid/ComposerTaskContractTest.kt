package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ComposerTaskContractTest {
    private val catalog = listOf(JSONObject().put("id", "test").put("defaultReasoningEffort", "medium")
        .put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "high"))))

    @Test fun explicitEffortUsesCorrectNativeFields() {
        assertEquals("high", TurnRequestContract.build("t", "执行", "test", "default", catalog,
            effort = "high").getString("effort"))
        val plan = TurnRequestContract.build("t", "计划", "test", "plan", catalog, effort = "high")
        assertEquals("high", plan.getJSONObject("collaborationMode").getJSONObject("settings").getString("reasoning_effort"))
        assertFalse(plan.has("effort"))
        // Native capability metadata may be newer than the picker. Preserve
        // the request, including model-defined effort values.
        assertEquals("provider-defined", TurnRequestContract.build("t", "执行", "test",
            "default", emptyList(), effort = "provider-defined").getString("effort"))
    }

    @Test fun planTaskOverridesConfiguredEffortWithoutDroppingReferences() {
        val models = listOf(JSONObject().put("id", "test").put("model", "configured-test")
            .put("defaultReasoningEffort", "low")
            .put("supportedReasoningEfforts", JSONArray()
                .put(JSONObject().put("reasoningEffort", "medium"))
                .put(JSONObject().put("reasoningEffort", "high"))))
        val config = JSONObject().put("model", "configured-test").put("plan_mode_reasoning_effort", "medium")
        val inherited = TurnRequestContract.build("t", "计划", "", "plan", models, effectiveConfig = config)
        assertEquals("medium", inherited.getJSONObject("collaborationMode").getJSONObject("settings").getString("reasoning_effort"))
        val refs = listOf(ComposerReference("file", "a.txt", "/workspace/a.txt"),
            ComposerReference("skill", "review", "/skills/review/SKILL.md"))
        val explicit = TurnRequestContract.build("t", "计划", "test", "plan", models,
            effort = "high", references = refs, effectiveConfig = config)
        assertEquals("high", explicit.getJSONObject("collaborationMode").getJSONObject("settings").getString("reasoning_effort"))
        assertTrue(explicit.getJSONArray("input").getJSONObject(0).getString("text").contains("/workspace/a.txt"))
        assertEquals("skill", explicit.getJSONArray("input").getJSONObject(1).getString("type"))
    }

    @Test fun typedInputsSharedBySendQueueAndSteer() {
        val refs = listOf(ComposerReference("file", "a.txt", "/workspace/a.txt"),
            ComposerReference("skill", "review", "/skills/review/SKILL.md"))
        val images = listOf("/images/a.png")
        val shared = TurnRequestContract.input("检查", images, refs)
        assertTrue(shared.getJSONObject(0).getString("text").contains("/workspace/a.txt"))
        assertEquals("localImage", shared.getJSONObject(1).getString("type"))
        assertEquals("skill", shared.getJSONObject(2).getString("type"))
        assertEquals(shared.toString(), TurnRequestContract.steer("t", "turn", "检查", images, refs).getJSONArray("input").toString())
        assertEquals(shared.toString(), TurnRequestContract.build("t", "检查", "test", "default", catalog,
            images, references = refs).getJSONArray("input").toString())
    }

    @Test fun optionsAndReferencesPersistAndMigrateIndependently() {
        val options = ComposerTaskOptions("test", "plan", "high")
        val ref = ComposerReference("file", "a.txt", "/workspace/a.txt")
        var drafts = ThreadComposerDrafts.options(emptyMap(), null, options)
        drafts = ThreadComposerDrafts.references(drafts, null, listOf(ref))
        drafts = ThreadComposerDrafts.attachNewThread(drafts, "A")
        drafts = ThreadComposerDrafts.text(drafts, "B", "其他任务")
        val restored = DraftPersistence.decode(DraftPersistence.encode(drafts))
        assertEquals(options, restored["A"]?.options)
        assertEquals(listOf(ref), restored["A"]?.references)
        assertEquals(ComposerTaskOptions(), restored["B"]?.options)
        val accepted = ThreadComposerDrafts.accepted(restored, "A", "", emptyList(), listOf(ref))
        assertEquals(options, accepted["A"]?.options)
        assertTrue(accepted["A"]?.references.orEmpty().isEmpty())
    }
}
