package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TimelineSparseSummaryTest {
    @Test fun sparse64BitIndexesDoNotAllocateMissingSegmentsAndKeepOrder() {
        val base = TimelineItem("reason", "reasoning", "", "streaming",
            JSONObject().put("type", "reasoning").put("id", "reason").toString(), "turn")
        val first = base.appendNative("summary", "后段", Long.MAX_VALUE, "streaming")
        val second = first.appendNative("summary", "首段", 0, "streaming")
        val last = second.appendNative("summary", "续", Long.MAX_VALUE, "streaming")
        assertEquals("后段", first.text)
        assertEquals("首段\n\n后段续", last.text)
        assertEquals(2, JSONObject(last.evidence).getJSONArray("summary").length())
        assertNotEquals(second, last)
    }
}
