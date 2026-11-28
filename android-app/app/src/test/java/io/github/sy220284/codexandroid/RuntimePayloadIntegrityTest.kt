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
    @Test fun installsIdenticalFilesOnceAndRetiresOnlyOldProductVersions() {
        val parent = Files.createTempDirectory("runtime-dedup").toFile()
        val active = File(parent, "a".repeat(64)).apply { mkdir() }
        val stale = File(parent, "b".repeat(64)).apply { mkdir(); File(this, "old").writeText("old") }
        val unrelated = File(parent, "user-data").apply { mkdir() }
        try {
            val payload = "same".toByteArray()
            val sha = RuntimePayloadIntegrity.manifestDigest(payload)
            val entries = RuntimePayloadIntegrity.manifest(
                "git/lib/first.so\t$sha\t4\npython/lib/second.so\t$sha\t4\n".toByteArray())
            val zip = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { out ->
                    out.putNextEntry(ZipEntry("git/lib/first.so"))
                    out.write(payload); out.closeEntry()
                }
            }.toByteArray()
            RuntimePayloadIntegrity.install(active, zip.inputStream(), entries,
                RuntimePayloadIntegrity.manifestDigest(zip))
            val first = File(active, "git/lib/first.so")
            val second = File(active, "python/lib/second.so")
            assertTrue(Files.isSameFile(first.toPath(), second.toPath()))
            assertTrue(RuntimePayloadIntegrity.matches(active, entries))
            second.delete(); second.writeBytes(payload)
            assertFalse(RuntimePayloadIntegrity.matches(active, entries))
            RuntimePayloadIntegrity.removeRetired(parent, active)
            assertFalse(stale.exists())
            assertTrue(active.exists())
            assertTrue(unrelated.exists())
        } finally { RuntimePayloadIntegrity.removeTree(parent) }
    }

    @Test fun rejectsCorruptArchiveAndUnsafeManifest() {
        val payload = "same".toByteArray()
        val sha = RuntimePayloadIntegrity.manifestDigest(payload)
        for (name in listOf("../escape", "/absolute", "bad/../path", "bad//path")) {
            assertThrows(IllegalArgumentException::class.java) {
                RuntimePayloadIntegrity.manifest("$name\t$sha\t4\n".toByteArray())
            }
        }
        val entries = RuntimePayloadIntegrity.manifest("file\t$sha\t4\n".toByteArray())
        val root = Files.createTempDirectory("runtime-bad-digest").toFile()
        try {
            val archive = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { out ->
                    out.putNextEntry(ZipEntry("file"))
                    out.write(payload); out.closeEntry()
                }
            }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                RuntimePayloadIntegrity.install(root, archive.inputStream(), entries, "0".repeat(64))
            }
        } finally { RuntimePayloadIntegrity.removeTree(root) }
    }

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
            val sha = RuntimePayloadIntegrity.manifestDigest("original".toByteArray())
            val entries = RuntimePayloadIntegrity.manifest("module.py\t$sha\t8\n".toByteArray())
            fun matches() = RuntimePayloadIntegrity.matches(root, entries)
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
