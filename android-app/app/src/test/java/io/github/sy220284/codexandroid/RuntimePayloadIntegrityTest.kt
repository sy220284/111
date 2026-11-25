package io.github.sy220284.codexandroid

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimePayloadIntegrityTest {
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
