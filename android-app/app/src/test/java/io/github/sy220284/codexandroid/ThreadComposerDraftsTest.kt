package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadComposerDraftsTest {
    @Test fun unsentDraftsAreNeverEvictedWhenMoreThan128ThreadsExist() {
        val source = (1..200).fold(emptyMap<String, ComposerDraft>()) { current, index ->
            ThreadComposerDrafts.text(current, "thread-$index", "未发送指令 $index")
        }
        assertEquals(200, source.size)
        assertEquals("未发送指令 1", source["thread-1"]?.text)
        assertEquals("未发送指令 200", source["thread-200"]?.text)
    }

    @Test fun emptyDraftIsPrunedButImageOnlyDraftIsKept() {
        val withText = ThreadComposerDrafts.text(emptyMap(), "A", "待发送")
        assertFalse(ThreadComposerDrafts.text(withText, "A", "").containsKey("A"))
        val withImage = ThreadComposerDrafts.images(emptyMap(), "B", listOf("image.png"))
        val clearedText = ThreadComposerDrafts.text(withImage, "B", "")
        assertEquals(listOf("image.png"), clearedText["B"]?.images)
        assertFalse(ThreadComposerDrafts.images(clearedText, "B", emptyList()).containsKey("B"))
    }

    @Test fun switchingSessionsKeepsPromptsAndAttachmentsIsolated() {
        var drafts = emptyMap<String, ComposerDraft>()
        drafts = ThreadComposerDrafts.text(drafts, "A", "修改登录流程")
        drafts = ThreadComposerDrafts.images(drafts, "A", listOf("/sandbox/a.png"))
        drafts = ThreadComposerDrafts.text(drafts, "B", "检查终端")
        assertEquals("修改登录流程", drafts["A"]?.text)
        assertEquals(listOf("/sandbox/a.png"), drafts["A"]?.images)
        assertEquals("检查终端", drafts["B"]?.text)
        assertTrue(drafts["B"]?.images.orEmpty().isEmpty())
    }

    @Test fun firstSubmissionTransfersDraftToCreatedThread() {
        val pending = ThreadComposerDrafts.images(
            ThreadComposerDrafts.text(emptyMap(), null, "初始化项目"),
            null, listOf("/sandbox/first.png")
        )
        val moved = ThreadComposerDrafts.attachNewThread(pending, "real-123")
        assertFalse(moved.containsKey(ThreadComposerDrafts.NEW_THREAD))
        assertEquals("初始化项目", moved["real-123"]?.text)
        assertEquals(listOf("/sandbox/first.png"), moved["real-123"]?.images)
        val cleared = ThreadComposerDrafts.accepted(
            moved, "real-123", "初始化项目", listOf("/sandbox/first.png")
        )
        assertFalse(cleared.containsKey("real-123"))
    }

    @Test fun rejectedFirstTurnRestoresTextAndImagesWithoutDiscardingConcurrentDraft() {
        val pending = ThreadComposerDrafts.attachNewThread(
            ThreadComposerDrafts.images(
                ThreadComposerDrafts.text(emptyMap(), null, "检查设置"),
                null, listOf("pending.jpg")
            ), "created-a"
        )
        val concurrent = ThreadComposerDrafts.text(pending, null, "另一个草稿")
        val restored = ThreadComposerDrafts.reclaimRejectedNewThread(concurrent, "created-a")
        assertFalse(restored.containsKey("created-a"))
        assertEquals("另一个草稿\n检查设置", restored[ThreadComposerDrafts.NEW_THREAD]?.text)
        assertEquals(listOf("pending.jpg"), restored[ThreadComposerDrafts.NEW_THREAD]?.images)
    }

    @Test fun acceptedOnlyClearsUnchangedFields() {
        var drafts = ThreadComposerDrafts.text(emptyMap(), "A", "第一版")
        drafts = ThreadComposerDrafts.images(drafts, "A", listOf("old.png"))
        drafts = ThreadComposerDrafts.text(drafts, "A", "新输入仍保留")
        drafts = ThreadComposerDrafts.images(drafts, "A", listOf("old.png", "new.png"))
        val after = ThreadComposerDrafts.accepted(drafts, "A", "第一版", listOf("old.png"))
        assertEquals("新输入仍保留", after["A"]?.text)
        assertEquals(listOf("old.png", "new.png"), after["A"]?.images)
    }

    @Test fun acceptedSteerDoesNotDeletePendingImages() {
        val drafts = ThreadComposerDrafts.images(
            ThreadComposerDrafts.text(emptyMap(), "A", "补充要求"),
            "A", listOf("pending.png")
        )
        val after = ThreadComposerDrafts.accepted(drafts, "A", "补充要求", emptyList())
        assertEquals("", after["A"]?.text)
        assertEquals(listOf("pending.png"), after["A"]?.images)
    }
}
