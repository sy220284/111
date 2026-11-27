package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BackupCheckpointValidationTest {
    @Test fun validGraphAndDamagedOrMissingObjects() {
        val root = Files.createTempDirectory("portable-checkpoint").toFile()
        try {
            val base = File(root, "files/" + AnswerCheckpointFormat.ROOT)
            val blobs = File(base, AnswerCheckpointFormat.OBJECTS).apply { check(mkdirs()) }
            val manifests = File(base, AnswerCheckpointFormat.MANIFESTS).apply { check(mkdirs()) }
            val temp = File(root, "data").apply { writeText("original source") }
            val hash = AnswerCheckpointFormat.digest(temp)
            val blob = temp.copyTo(File(blobs, hash))
            val row = JSONObject().put("path", "source.kt").put("hash", hash).put("size", blob.length())
            val manifest = File(manifests, AnswerCheckpointFormat.manifestName("12345678-1234-1234-1234-123456789abc"))
            manifest.writeText(JSONObject().put("cwd", "/workspace").put("entries", JSONArray().put(row)).toString())
            BackupCheckpointValidation.verify(root)
            blob.writeText("different data")
            assertThrows(IOException::class.java) { BackupCheckpointValidation.verify(root) }
            assertTrue(blob.delete())
            assertThrows(IOException::class.java) { BackupCheckpointValidation.verify(root) }
            temp.copyTo(blob)
            row.put("path", "../escape")
            manifest.writeText(JSONObject().put("cwd", "/workspace").put("entries", JSONArray().put(row)).toString())
            assertThrows(IllegalArgumentException::class.java) { BackupCheckpointValidation.verify(root) }
        } finally { check(root.deleteRecursively()) }
    }
}
