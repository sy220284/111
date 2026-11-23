package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThreadDiffHistoryTest {
    private fun changed(path: String, diff: String) = JSONObject()
        .put("type", "fileChange")
        .put("changes", JSONArray().put(JSONObject()
            .put("path", path).put("diff", diff)))

    private fun turn(vararg items: JSONObject) =
        JSONObject().put("items", JSONArray().apply { items.forEach { put(it) } })

    @Test fun restoresLatestTurnWithFileChanges() {
        val earlier = turn(changed("old.kt", "@@ -1 +1 @@\n-old\n+first"))
        val latest = turn(changed("new.kt", "@@ -1 +1 @@\n-first\n+second"))
        val result = ThreadDiffHistory.latest(JSONArray().put(earlier).put(latest))
        assertEquals("new.kt", UnifiedDiff.parse(result).single().path)
        assertTrue(result.contains("+second"))
        assertFalse(result.contains("old.kt"))
    }

    @Test fun preservesDistinctFileSectionsAndExistingGitHeaders() {
        val original = "diff --git a/a.kt b/a.kt\n@@ -1 +1 @@\n-old\n+new"
        val latest = turn(
            changed("a.kt", original),
            changed("b.kt", "@@ -0,0 +1 @@\n+hello")
        )
        val files = UnifiedDiff.parse(ThreadDiffHistory.latest(JSONArray().put(latest)))
        assertEquals(listOf("a.kt", "b.kt"), files.map { it.path })
        assertEquals(1, files[0].additions)
        assertEquals(1, files[1].additions)
    }

    @Test fun ignoresEmptyChangesAndFindsEarlierUsefulTurn() {
        val first = turn(changed("main.kt", "@@ -1 +1 @@\n-a\n+b"))
        val later = turn(JSONObject().put("type", "agentMessage").put("text", "done"))
        val result = ThreadDiffHistory.latest(JSONArray().put(first).put(later))
        assertTrue(result.contains("main.kt"))
        assertEquals("", ThreadDiffHistory.latest(JSONArray().put(later)))
    }
}
