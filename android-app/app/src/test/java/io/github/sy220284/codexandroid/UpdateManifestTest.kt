package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)

    private fun manifest(algorithm: String = "hdiffpatch-window-zstd-v1"): String = """
        {
          "schema": 2,
          "targetVersion": "0.1.0-111.4",
          "apks": [
            {"abi":"arm64-v8a","name":"codex-android-arm64-v8a-0.1.0-111.4.apk",
             "size":146386642,"sha256":"$b"}
          ],
          "patches": [
            {"abi":"arm64-v8a","fromVersion":"0.1.0-111.3","toVersion":"0.1.0-111.4",
             "asset":"delta-0.1.0-111.3-to-0.1.0-111.4-arm64-v8a.hpatch",
             "size":930658,"sha256":"$c","sourceSha256":"$a",
             "targetSha256":"$b","algorithm":"$algorithm"}
          ]
        }
    """.trimIndent()

    @Test fun newerReleaseAndExactDeltaAreSelected() {
        val result = UpdateManifest.parse(manifest())
        assertTrue(result.isNewer(3))
        assertFalse(result.isNewer(4))
        assertFalse(result.isNewer(5))
        assertEquals("0.1.0-111.4", result.targetVersion)
        assertNotNull(result.patchFor("0.1.0-111.3", a.uppercase()))
        assertNull(result.patchFor("0.1.0-111.2", a))
        assertNull(result.patchFor("0.1.0-111.3", b))
        assertEquals(4L, UpdateManifest.versionCode("0.1.0-111.4"))
        assertNull(UpdateManifest.versionCode("0.1.0"))
        assertNull(UpdateManifest.versionCode("1.0.0-111.0"))
    }

    @Test fun archiveMustBeTheExactNewerVersionDeclaredByTheRelease() {
        val current = "0.1.0-111.4"
        assertTrue(UpdateManifest.matchesUpgrade(current, 4L, current, 4L, 3L))
        assertFalse(UpdateManifest.matchesUpgrade(current, 4L, "0.1.0-111.3", 4L, 3L))
        assertFalse(UpdateManifest.matchesUpgrade(current, 4L, current, 5L, 3L))
        assertFalse(UpdateManifest.matchesUpgrade(current, 4L, current, 4L, 4L))
        assertFalse(UpdateManifest.matchesUpgrade(current, 4L, current, 4L, 5L))
    }

    @Test fun unknownAlgorithmUsesFullApk() {
        assertNull(UpdateManifest.parse(manifest("unknown")).patchFor("0.1.0-111.3", a))
    }

    @Test fun invalidIntegrityMetadataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest().replace(b, "broken"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest().replace("arm64-v8a", "x86_64"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifest.parse(manifest().replace("0.1.0-111.4", "../evil"))
        }
    }
}
