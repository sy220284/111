package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountLoginContractTest {
    @Test fun browserLoginUsesOfficialTaggedFields() {
        val result = AccountLoginContract.browser(JSONObject().put("type", "chatgpt")
            .put("loginId", "auth-1").put("authUrl", "https://auth.openai.com/authorize"))
        assertEquals("auth-1", result.id)
        assertTrue(result.url.startsWith("https://"))
    }

    @Test fun deviceCodeRequiresBothVerificationUrlAndCode() {
        val result = AccountLoginContract.device(JSONObject().put("type", "chatgptDeviceCode")
            .put("loginId", "auth-2")
            .put("verificationUrl", "https://auth.openai.com/device")
            .put("userCode", "AA-BB"))
        assertEquals("AA-BB", result.code)
        try {
            AccountLoginContract.device(JSONObject().put("type", "chatgptDeviceCode")
                .put("loginId", "auth-2").put("userCode", "AA-BB"))
            fail("Must not expose an incomplete login flow")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun staleLoginNotificationDoesNotOverrideNewAttempt() {
        val response = AccountLoginContract.completion(JSONObject()
            .put("loginId", "old-auth").put("success", true), "new-auth")
        assertNull(response)
    }

    @Test fun missingLoginIdNeverCompletesAnUnrelatedAttempt() {
        assertNull(AccountLoginContract.completion(
            JSONObject().put("success", true), "new-auth"))
        assertNull(AccountLoginContract.completion(
            JSONObject().put("loginId", "old-auth").put("success", true), ""))
        assertNull(AccountLoginContract.completion(
            JSONObject().put("loginId", JSONObject.NULL).put("success", true), "new-auth"))
    }

    @Test fun failedLoginPreservesErrorAndSuccessfulLoginClearsIt() {
        val failure = AccountLoginContract.completion(JSONObject()
            .put("loginId", "auth-3").put("success", false)
            .put("error", "Authorization expired"), "auth-3")
        assertNotNull(failure)
        assertFalse(failure!!.success)
        assertEquals("Authorization expired", failure.error)
        val success = AccountLoginContract.completion(JSONObject()
            .put("loginId", "auth-3").put("success", true), "auth-3")
        assertTrue(success!!.success)
    }
}
