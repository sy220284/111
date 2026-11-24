package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SidebarQuotaTest {
    private fun window(minutes: Int, used: Any) =
        JSONObject().put("windowDurationMins", minutes).put("usedPercent", used)

    private fun meter(primary: JSONObject, secondary: JSONObject) =
        JSONObject().put("primary", primary).put("secondary", secondary)

    @Test fun windowsAreIdentifiedByDurationNotPosition() {
        val response = JSONObject().put("rateLimitsByLimitId", JSONObject().put("codex",
            meter(window(10080, 28), window(300, 62))))
        val quota = SidebarQuota.from(response)
        assertEquals(.72f, requireNotNull(quota.weekly), .0001f)
        assertEquals(.38f, requireNotNull(quota.fiveHours), .0001f)
        assertTrue(quota.description(false, false).contains("周额度剩余 72%"))
        assertTrue(quota.description(false, false).contains("五小时额度剩余 38%"))
    }

    @Test fun fullEmptyAndLegacyWindowsAreRepresentedExactly() {
        val quota = SidebarQuota.from(JSONObject().put("rateLimits",
            meter(window(300, 100), window(10080, 0))))
        assertEquals(1f, requireNotNull(quota.weekly), 0f)
        assertEquals(0f, requireNotNull(quota.fiveHours), 0f)
    }

    @Test fun unrelatedMetersAndInvalidOrMissingValuesRemainUnknown() {
        val other = meter(window(300, 2), window(10080, 2)).put("limitId", "other")
        val result = JSONObject().put("rateLimits", other)
            .put("rateLimitsByLimitId", JSONObject().put("other", other))
        assertEquals(SidebarQuota(null, null), SidebarQuota.from(result))
        result.put("rateLimitsByLimitId", JSONObject().put("codex",
            meter(window(300, "12"), window(10080, 101))))
        assertEquals(SidebarQuota(null, null), SidebarQuota.from(result))
        val partial = SidebarQuota.from(JSONObject().put("rateLimits",
            meter(window(300, 50), window(60, 2))))
        assertNull(partial.weekly)
        assertEquals(.5f, requireNotNull(partial.fiveHours), 0f)
        assertTrue(partial.description(true, false).contains("上次读取"))
        assertTrue(SidebarQuota.from(null).description(false, true).contains("正在读取"))
    }
}
