package io.github.sy220284.codexandroid

/** 每条会话独立保留未提交的文字与图片，避免侧栏切换时把指令送错会话。 */
data class ComposerDraft(
    val text: String = "",
    val images: List<String> = emptyList()
)

object ThreadComposerDrafts {
    const val NEW_THREAD = "__new_thread__"

    fun key(threadId: String?): String = threadId?.takeIf { it.isNotBlank() } ?: NEW_THREAD

    fun text(
        previous: Map<String, ComposerDraft>, threadId: String?, value: String
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        return previous + (id to (previous[id] ?: ComposerDraft()).copy(text = value))
    }

    fun images(
        previous: Map<String, ComposerDraft>, threadId: String?, paths: List<String>
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        return previous + (id to (previous[id] ?: ComposerDraft()).copy(images = paths.toList()))
    }

    /** 只清除服务器已经接收的原内容；提交期间输入的新文字或图片继续保留。 */
    fun accepted(
        previous: Map<String, ComposerDraft>,
        threadId: String?,
        submittedText: String,
        submittedImages: List<String>
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        val original = previous[id] ?: return previous
        val next = original.copy(
            text = if (original.text == submittedText) "" else original.text,
            images = if (original.images == submittedImages) emptyList() else original.images
        )
        return if (next.text.isBlank() && next.images.isEmpty()) previous - id
            else previous + (id to next)
    }

    /**
     * A definitively rejected first turn may leave an empty server thread.
     * Once that empty thread has been verified and deleted, restore its draft.
     */
    fun reclaimRejectedNewThread(
        previous: Map<String, ComposerDraft>, createdThreadId: String
    ): Map<String, ComposerDraft> {
        require(createdThreadId.isNotBlank()) { "会话编号不能为空" }
        val rejected = previous[createdThreadId] ?: return previous
        val concurrent = previous[NEW_THREAD]
        val recovered = if (concurrent == null || concurrent == rejected) rejected
            else ComposerDraft(
                text = listOf(concurrent.text, rejected.text)
                    .filter { it.isNotBlank() }.distinct().joinToString("\n"),
                images = (concurrent.images + rejected.images).distinct()
            )
        return (previous - createdThreadId) + (NEW_THREAD to recovered)
    }

    /** 首条指令创建真正 threadId 后，草稿跟随新会话迁移。 */
    fun attachNewThread(
        previous: Map<String, ComposerDraft>, createdThreadId: String
    ): Map<String, ComposerDraft> {
        require(createdThreadId.isNotBlank()) { "会话编号不能为空" }
        val snapshot = previous[NEW_THREAD] ?: return previous
        return (previous - NEW_THREAD) + (createdThreadId to snapshot)
    }
}
