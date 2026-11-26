package io.github.sy220284.codexandroid

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RemoteTransferCheckpointTest {
    @Test fun resumesOnlySameSourceAndVerifiesFinalDigest() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val store = RemoteTransferCheckpoint(dir)
            val first = ByteArray(RemoteTransferCheckpoint.CHUNK_BYTES) { (it % 251).toByte() }
            val tail = byteArrayOf(7, 8, 9)
            val sha = RemoteTransferCheckpoint.hex(MessageDigest.getInstance("SHA-256").digest(first + tail))
            val source = RemoteTransferCheckpoint.Source("account-thread-device-file", (first.size + tail.size).toLong(), sha)
            val entry = store.begin(source)
            store.append(entry, 0, first)
            assertSame(entry, store.begin(source))
            assertEquals(first.size.toLong(), entry.offset)
            assertTrue(runCatching { store.append(entry, 0, tail) }.isFailure)
            store.append(entry, entry.offset, tail)
            store.verify(entry)
            val next = store.begin(source.copy(owner = "another-account"))
            assertEquals(0L, next.offset)
            assertFalse(entry.file.exists())
            assertTrue(runCatching { store.verify(entry) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
    @Test fun strictChunkDecodingWithoutReencoding() {
        val random = kotlin.random.Random(116)
        for (size in 0..2048) {
            val bytes = random.nextBytes(size)
            val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
            assertArrayEquals(bytes, RemoteTransferCheckpoint.decodeChunk(encoded, size.toLong()))
        }
        for ((encoded, size) in listOf("AB==" to 1L, "AAB=" to 2L, "AA=A" to 1L, "A A=" to 2L,
            "AAAA" to 1L, "AA==" to 2L, "AA" to 1L, "____" to 3L)) {
            assertTrue(encoded, runCatching { RemoteTransferCheckpoint.decodeChunk(encoded, size) }.isFailure)
        }
    }
    @Test fun rejectsCorruptionAndSupportsEmptyFile() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val store = RemoteTransferCheckpoint(dir)
            val sha = RemoteTransferCheckpoint.hex(MessageDigest.getInstance("SHA-256").digest(byteArrayOf()))
            val entry = store.begin(RemoteTransferCheckpoint.Source("owner", 0, sha))
            store.verify(entry)
            val invalid = store.begin(RemoteTransferCheckpoint.Source("owner", 3, sha))
            store.append(invalid, 0, byteArrayOf(1, 2, 3))
            assertTrue(runCatching { store.verify(invalid) }.isFailure)
            assertFalse(invalid.file.exists())
            assertTrue(runCatching { RemoteTransferCheckpoint.Source("owner", RemoteTransferCheckpoint.MAX_BYTES + 1, sha) }.isFailure)
        } finally { dir.deleteRecursively() }
    }
}
