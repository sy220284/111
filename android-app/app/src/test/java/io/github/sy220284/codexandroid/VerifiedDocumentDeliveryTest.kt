package io.github.sy220284.codexandroid

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class VerifiedDocumentDeliveryTest {
    @Test fun fullBytesAreReadBackAndChecked() = runBlocking {
        val bytes = ByteArray(350_000) { (it % 251).toByte() }
        val target = ByteArrayOutputStream()
        var written = false
        var verified = 0L
        VerifiedDocumentDelivery.transfer(bytes.size.toLong(), null,
            { ByteArrayInputStream(bytes) }, { target }, { ByteArrayInputStream(target.toByteArray()) },
            permit = {}, onWritten = { written = true }, onVerified = { verified = it })
        assertArrayEquals(bytes, target.toByteArray())
        assertTrue(written)
        assertEquals(bytes.size.toLong(), verified)
    }

    @Test fun corruptedDestinationAndRevokedOwnerDoNotSucceed() = runBlocking {
        val bytes = ByteArray(100_000) { 7 }
        val target = ByteArrayOutputStream()
        assertTrue(runCatching {
            VerifiedDocumentDelivery.transfer(bytes.size.toLong(), null,
                { ByteArrayInputStream(bytes) }, { target }, { ByteArrayInputStream(ByteArray(bytes.size)) }, {})
        }.isFailure)
        var permitted = true
        assertTrue(runCatching {
            VerifiedDocumentDelivery.transfer(bytes.size.toLong(), null,
                { ByteArrayInputStream(bytes) }, { ByteArrayOutputStream() }, { ByteArrayInputStream(bytes) },
                { check(permitted) }, onWritten = { permitted = false })
        }.isFailure)
    }
}
