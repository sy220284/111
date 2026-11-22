package io.github.sy220284.codexandroid

/**
 * Each Codex thread can own one live turn. Keeping ownership per thread prevents
 * a background completion or late turn/start response from altering another chat.
 */
internal data class ThreadExecution(
    val turnId: String? = null,
    val images: List<String> = emptyList()
)

internal object ThreadExecutionPolicy {
    fun reserve(
        previous: Map<String, ThreadExecution>, threadId: String, images: List<String>
    ): Map<String, ThreadExecution> {
        require(threadId.isNotBlank()) { "会话编号不能为空" }
        check(threadId !in previous) { "该会话已有执行中的任务" }
        return previous + (threadId to ThreadExecution(images = images.toList()))
    }

    fun started(
        previous: Map<String, ThreadExecution>, threadId: String, turnId: String?
    ): Map<String, ThreadExecution> {
        require(threadId.isNotBlank()) { "会话编号不能为空" }
        val existing = previous[threadId] ?: ThreadExecution()
        return previous + (threadId to existing.copy(
            turnId = turnId?.takeIf { it.isNotBlank() } ?: existing.turnId
        ))
    }

    fun finish(
        previous: Map<String, ThreadExecution>, threadId: String
    ): Map<String, ThreadExecution> = previous - threadId
}
