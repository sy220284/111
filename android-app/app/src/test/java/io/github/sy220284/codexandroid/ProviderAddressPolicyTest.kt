package io.github.sy220284.codexandroid
import org.junit.Assert.*
import org.junit.Test
class ProviderAddressPolicyTest {
    @Test fun validatesRealHttpEndpoints() {
        assertTrue(ProviderAddressPolicy.valid("https://example.com/v1"))
        assertTrue(ProviderAddressPolicy.valid("http://127.0.0.1:8080"))
        assertFalse(ProviderAddressPolicy.valid("https://"))
        assertFalse(ProviderAddressPolicy.valid("https://example.com:99999"))
        assertFalse(ProviderAddressPolicy.valid("https://user:pass@example.com"))
        assertFalse(ProviderAddressPolicy.valid("file:///tmp"))
    }
}
