package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class InstallSignaturePolicyTest {
    @Test fun acceptsSameSignedPublisher() {
        assertTrue(InstallSignaturePolicy.accepts(
            "io.github.sy220284.codexandroid", "io.github.sy220284.codexandroid",
            setOf("a1b2"), setOf("a1b2")))
    }

    @Test fun rejectsDifferentPackageOrSignerAndMissingSignature() {
        val packageName = "io.github.sy220284.codexandroid"
        assertFalse(InstallSignaturePolicy.accepts(
            packageName, "example.evil", setOf("a1b2"), setOf("a1b2")))
        assertFalse(InstallSignaturePolicy.accepts(
            packageName, packageName, setOf("a1b2"), setOf("bad1")))
        assertFalse(InstallSignaturePolicy.accepts(
            packageName, packageName, emptySet(), emptySet()))
    }
}
