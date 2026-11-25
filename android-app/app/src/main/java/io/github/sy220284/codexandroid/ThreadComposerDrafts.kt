package io.github.sy220284.codexandroid

/** 每条会话独立保留未提交的文字与图片，避免侧栏切换时把指令送错会话。 */
data class ComposerDraft(
    val text: String = "",
    val images: List<String> = emptyList(),
    val options: ComposerTaskOptions = ComposerTaskOptions(),
    val references: List<ComposerReference> = emptyList()
) {
    val hasInput get() = text.isNotBlank() || images.isNotEmpty() || references.isNotEmpty()
    val hasStoredState get() = hasInput || options != ComposerTaskOptions()
}

object ThreadComposerDrafts {
    const val NEW_THREAD = "__new_thread__"
    const val AGENT_ACTION = "__agent_action__"
    fun agentKey(workspace: String): String = "$AGENT_ACTION:$workspace"
    // Never evict an unsent draft: arbitrary LRU limits silently discard user text.
    // Only remove empty entries, which contain no data the user needs to recover.
    private fun updated(
        previous: Map<String, ComposerDraft>, id: String, draft: ComposerDraft
    ): Map<String, ComposerDraft> =
        if (!draft.hasStoredState) previous - id
        else (previous - id) + (id to draft)

    fun key(threadId: String?, workspace: String? = null): String =
        threadId?.takeIf { it.isNotBlank() } ?: workspace?.let { "$NEW_THREAD:$it" } ?: NEW_THREAD

    fun text(
        previous: Map<String, ComposerDraft>, threadId: String?, value: String
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        return updated(previous, id, (previous[id] ?: ComposerDraft()).copy(text = value))
    }

    fun images(
        previous: Map<String, ComposerDraft>, threadId: String?, paths: List<String>
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        return updated(previous, id, (previous[id] ?: ComposerDraft()).copy(images = paths.toList()))
    }

    fun options(previous: Map<String, ComposerDraft>, threadId: String?, value: ComposerTaskOptions): Map<String, ComposerDraft> {
        val id = key(threadId)
        return updated(previous, id, (previous[id] ?: ComposerDraft()).copy(options = value))
    }

    fun references(previous: Map<String, ComposerDraft>, threadId: String?, value: List<ComposerReference>): Map<String, ComposerDraft> {
        val id = key(threadId)
        return updated(previous, id, (previous[id] ?: ComposerDraft()).copy(references = value.distinct()))
    }

    /** 只清除服务器已经接收的原内容；提交期间输入的新文字或附件继续保留。 */
    fun accepted(
        previous: Map<String, ComposerDraft>,
        threadId: String?,
        submittedText: String,
        submittedImages: List<String>,
        submittedReferences: List<ComposerReference> = emptyList()
    ): Map<String, ComposerDraft> {
        val id = key(threadId)
        val original = previous[id] ?: return previous
        val next = original.copy(
            text = if (original.text == submittedText) "" else original.text,
            images = original.images.filterNot { it in submittedImages },
            references = original.references.filterNot { it in submittedReferences }
        )
        return updated(previous, id, next)
    }

    /**
     * A definitively rejected first turn may leave an empty server thread.
     * Once that empty thread has been verified and deleted, restore its draft.
     */
    fun reclaimRejectedNewThread(
        previous: Map<String, ComposerDraft>, createdThreadId: String, newDraftKey: String = NEW_THREAD
    ): Map<String, ComposerDraft> {
        require(createdThreadId.isNotBlank()) { "会话编号不能为空" }
        val rejected = previous[createdThreadId] ?: return previous
        val concurrent = previous[newDraftKey]
        val recovered = if (concurrent == null || concurrent == rejected) rejected
            else ComposerDraft(
                text = listOf(concurrent.text, rejected.text)
                    .filter { it.isNotBlank() }.distinct().joinToString("\n"),
                images = (concurrent.images + rejected.images).distinct(),
                options = if (concurrent.options != ComposerTaskOptions()) concurrent.options else rejected.options,
                references = (concurrent.references + rejected.references).distinct()
            )
        return updated(previous - createdThreadId, newDraftKey, recovered)
    }

    /** 首条指令创建真正 threadId 后，草稿跟随新会话迁移。 */
    fun attachNewThread(
        previous: Map<String, ComposerDraft>, createdThreadId: String, newDraftKey: String = NEW_THREAD
    ): Map<String, ComposerDraft> {
        require(createdThreadId.isNotBlank()) { "会话编号不能为空" }
        val snapshot = previous[newDraftKey] ?: return previous
        return updated(previous - newDraftKey, createdThreadId, snapshot)
    }
}
