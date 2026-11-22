package io.github.sy220284.codexandroid

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class HostRequestFeedbackTest {
    @Test fun unsupportedHostCapabilityIsVisibleToUser() {
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        HostRequestFeedback.report(
            JSONObject().put("method", "item/tool/call")
                .put("reason", "未启用工具宿主"), errors)
        assertEquals("未启用工具宿主", errors.value["protocol"])
    }

    @Test fun missingErrorDescriptionProducesActionableStatus() {
        val errors = MutableStateFlow<Map<String, String>>(emptyMap())
        HostRequestFeedback.report(JSONObject(), errors)
        org.junit.Assert.assertTrue(
            errors.value["protocol"]!!.contains("未启用"))
    }
}
