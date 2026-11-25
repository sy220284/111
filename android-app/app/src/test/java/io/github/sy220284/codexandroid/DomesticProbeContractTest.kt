package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class DomesticProbeContractTest {
    @Test fun explicitConsentAndProviderScopeAreMandatory() {
        val body = DomesticProbeContract.params("provider-a", "model-a", true)
        assertEquals("provider-a", body.getString("modelProvider"))
        assertEquals("model-a", body.getString("model"))
        assertTrue(body.getBoolean("confirmPaidRequest"))
        assertEquals(3, body.length())
        assertThrows(IllegalArgumentException::class.java) { DomesticProbeContract.params("provider-a", "model-a", false) }
        assertThrows(IllegalArgumentException::class.java) { DomesticProbeContract.params("", "model-a", true) }
        assertThrows(IllegalArgumentException::class.java) { DomesticProbeContract.params("provider-a", "", true) }
    }
}
