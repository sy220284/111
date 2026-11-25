package io.github.sy220284.codexandroid

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimePayloadIntegrityTest {
    @Test fun sealsPayloadAndRemovesSealedOrDamagedDirectoriesWithoutTouchingExternalLinks() {
        val parent = Files.createTempDirectory("runtime-seal").toFile()
        val root = File(parent, "payload").apply { mkdir() }
        try {
            val nested = File(root, "lib").apply { mkdir() }
            val module = File(nested, "module.py").apply { writeText("signed") }
            val outside = File(parent, "outside").apply { writeText("untouched") }
            val outsidePermissions = Files.getPosixFilePermissions(outside.toPath())
            RuntimePayloadIntegrity.sealReadOnly(root)
            for (entry in listOf(root, nested, module)) {
                assertTrue(Files.getPosixFilePermissions(entry.toPath()).none {
                    it == PosixFilePermission.OWNER_WRITE || it == PosixFilePermission.GROUP_WRITE ||
                        it == PosixFilePermission.OTHERS_WRITE
                })
            }
            // A same-UID process may change modes; rebuilding must still avoid symlinks.
            Files.setPosixFilePermissions(nested.toPath(), setOf(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
            Files.createSymbolicLink(File(nested, "external").toPath(), outside.toPath())
            Files.setPosixFilePermissions(nested.toPath(), emptySet())
            RuntimePayloadIntegrity.removeTree(root)
            assertFalse(root.exists())
            assertEquals("untouched", outside.readText())
            assertEquals(outsidePermissions, Files.getPosixFilePermissions(outside.toPath()))
        } finally {
            RuntimePayloadIntegrity.removeTree(root)
            parent.deleteRecursively()
        }
    }

    @Test fun detectsModifiedMissingExtraAndLinkedFilesAndRebuildDoesNotFollowLinks() {
        val parent = Files.createTempDirectory("runtime-integrity").toFile()
        try {
            val root = File(parent, "payload").apply { mkdir() }
            val file = File(root, "module.py").apply { writeText("original") }
            val zip = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { out ->
                    out.putNextEntry(ZipEntry("module.py"))
                    out.write("original".toByteArray())
                    out.closeEntry()
                }
            }.toByteArray()
            fun matches() = RuntimePayloadIntegrity.matches(root, zip.inputStream())
            assertTrue(matches())
            file.writeText("changed"); assertFalse(matches())
            file.delete(); assertFalse(matches())
            file.writeText("original"); File(root, "extra.py").apply { writeText("extra"); assertFalse(matches()); delete() }
            val outside = File(parent, "outside").apply { writeText("original") }
            file.delete(); Files.createSymbolicLink(file.toPath(), outside.toPath())
            assertFalse(matches())
            RuntimePayloadIntegrity.removeTree(root)
            assertFalse(root.exists()); assertEquals("original", outside.readText())
        } finally { parent.deleteRecursively() }
    }
}
