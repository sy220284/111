package io.github.sy220284.codexandroid

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftPersistenceTest {
    private val image = "a180f2d5-5899-4444-bbb1-7e334ab798e4.png"

    @Test fun allUnsentTextsSurviveRoundTripBeyondLegacyLimit() {
        val drafts = (1..200).associate { "thread-$it" to ComposerDraft("未发送 $it") }
        val decoded = DraftPersistence.decode(DraftPersistence.encode(drafts))
        assertEquals(200, decoded.size)
        assertEquals("未发送 1", decoded["thread-1"]?.text)
        assertEquals("未发送 200", decoded["thread-200"]?.text)
    }

    @Test fun legacyTextOnlyRecordsRemainReadable() {
        val raw = JSONObject().put("old", JSONObject().put("text", "旧会话文字"))
        assertEquals("旧会话文字", DraftPersistence.decode(raw.toString())["old"]?.text)
    }

    @Test fun textAndImageOnlyDraftsPersistPortableImageNames() {
        val originals = mapOf(
            ThreadComposerDrafts.NEW_THREAD to ComposerDraft("新项目\n请核查配置"),
            "thread-1" to ComposerDraft("检查 PR", listOf("/private/$image")),
            "image-only" to ComposerDraft(images = listOf("/private/$image")),
            "empty" to ComposerDraft()
        )
        val raw = DraftPersistence.encode(originals)
        assertFalse(raw.contains("/private/"))
        val decoded = DraftPersistence.decode(raw)
        assertEquals(listOf(image), decoded["thread-1"]?.images)
        assertEquals(listOf(image), decoded["image-only"]?.images)
        assertEquals("新项目\n请核查配置", decoded[ThreadComposerDrafts.NEW_THREAD]?.text)
        assertFalse(decoded.containsKey("empty"))
    }

    @Test fun restoresOnlyExistingAppPrivateImages() {
        val root = Files.createTempDirectory("codex-image-recovery").toFile()
        try {
            val existing = File(root, image).apply { writeText("valid-file") }
            val result = DraftPersistence.resolveStoredImages(root, mapOf(
                "A" to ComposerDraft("文字", listOf(image, "../secret.txt", "missing.png")),
                "B" to ComposerDraft(images = listOf(image))
            ))
            assertEquals(listOf(existing.canonicalPath), result.drafts["A"]?.images)
            assertEquals(listOf(existing.canonicalPath), result.drafts["B"]?.images)
            assertEquals(2, result.missingImages)
            assertEquals("文字", result.drafts["A"]?.text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun unmanagedPathsCannotEnterSavedDrafts() {
        try {
            DraftPersistence.encode(mapOf("A" to ComposerDraft(images = listOf("/etc/passwd"))))
            fail("Unmanaged path must be rejected")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("未受管理"))
        }
    }
}
