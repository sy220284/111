package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File

class BackupIntegrityTest {
    private fun record(text: String): BackupIntegrity.Record {
        val bytes = text.toByteArray()
        return BackupIntegrity.Accumulator().apply { update(bytes, bytes.size) }.finish()
    }
    private fun manifest(records: Map<String, BackupIntegrity.Record>) =
        ByteArrayOutputStream().also { BackupIntegrity.write(it, records) }.toByteArray()

    @Test fun verifiesExactInventoryIncludingEmptyAndUnicodeFiles() {
        val records = mapOf("files/projects/报告.txt" to record("hello"), "files/projects/empty" to record(""))
        val encoded = manifest(records)
        BackupIntegrity.verify(ByteArrayInputStream(encoded), records)
        assertTrue(runCatching { BackupIntegrity.verify(ByteArrayInputStream(encoded), records - "files/projects/empty") }.isFailure)
        assertTrue(runCatching { BackupIntegrity.verify(ByteArrayInputStream(encoded), records + ("files/projects/报告.txt" to record("other"))) }.isFailure)
        assertTrue(runCatching { BackupIntegrity.verify(ByteArrayInputStream(encoded + byteArrayOf(0)), records) }.isFailure)
        assertTrue(runCatching { BackupIntegrity.verify(ByteArrayInputStream(encoded.copyOf(encoded.size - 1)), records) }.isFailure)
    }

    @Test fun stagedFileTamperingFailsBeforeRecovery() {
        val root = java.nio.file.Files.createTempDirectory("backup-integrity").toFile()
        try {
            val file = File(root, "data").apply { writeText("original") }
            val sources = listOf("files/projects/data" to file)
            val seal = manifest(BackupIntegrity.snapshot(sources))
            file.writeText("modified")
            assertTrue(runCatching { BackupIntegrity.verify(ByteArrayInputStream(seal), BackupIntegrity.snapshot(sources)) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
