package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionDirectoryEngineTest {
    @Test fun directoryFiltersAndOrdersPinnedRowsWithoutMutatingOriginal() {
        val rows = JSONArray()
            .put(JSONObject().put("id", "a").put("name", "工作甲").put("cwd", "/p").put("updatedAt", 1))
            .put(JSONObject().put("id", "b").put("name", "工作乙").put("cwd", "/p").put("updatedAt", 2))
            .put(JSONObject().put("id", "c").put("name", "其他").put("cwd", "/other").put("updatedAt", 3))
        val raw = JSONObject().put("data", rows)
        val before = raw.toString()
        val scope = SessionDirectoryScope(1L, 1L, null, false, false, "工作", true, "/p", null)
        val result = SessionDirectoryEngine.project(SessionDirectoryInput(scope, raw, null,
            AnswerVersionState(), null, setOf("a"))) { null }
        assertEquals(listOf("a", "b"), result.rows.map(SessionListPolicy::id))
        assertEquals(setOf("a"), result.pinned)
        assertEquals(before, raw.toString())
    }
}
