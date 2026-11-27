package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SafetyRecourseContractTest {
    private val metadata = """{"safety_recourse":{"schema_version":1,"domain":"cyber","source":"user_request","source_message":{"id":"m1","kind":"ordinary_message","role":"user"},"topic":"unauthorized_account_or_system_access","suggestions":[{"label":"账号恢复","prompt_text":"介绍账号恢复流程"},{"label":"防御措施","prompt_text":"如何保护账号安全"},{"label":"Enable Daybreak","prompt_text":"查看访问权限"}]}}"""
    @Test fun validatesAndFiltersEntitlementActions() {
        val safe = SafetyRecourseContract.parse(JSONObject(metadata))!!
        assertEquals("ready", safe.getString("status"))
        assertEquals(2, safe.getJSONArray("suggestions").length())
        assertNull(SafetyRecourseContract.parse(JSONObject(metadata.replace("user_request", "unknown"))))
        assertNull(SafetyRecourseContract.parse(JSONObject(metadata.replace("cyber", "bio"))))
        assertEquals("cyber", SafetyRecourseContract.errorDomain(JSONObject().put("codexErrorInfo", "cyberPolicy")))
        assertEquals("bio", SafetyRecourseContract.errorDomain(JSONObject().put("message", """{"error":{"code":"bio_policy"}}""")))
    }
}
