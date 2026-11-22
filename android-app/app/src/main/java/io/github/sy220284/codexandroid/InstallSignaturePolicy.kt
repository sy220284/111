package io.github.sy220284.codexandroid

/** The installed and downloaded APK must belong to the same signing identity. */
internal object InstallSignaturePolicy {
    fun accepts(
        installedPackage: String, newPackage: String,
        currentSignerDigests: Set<String>, newSignerDigests: Set<String>
    ): Boolean = installedPackage == newPackage &&
        currentSignerDigests.isNotEmpty() &&
        currentSignerDigests == newSignerDigests
}
