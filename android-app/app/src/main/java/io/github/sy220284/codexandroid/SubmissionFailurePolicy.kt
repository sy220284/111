package io.github.sy220284.codexandroid

/** A request timeout is ambiguous until the thread is reconciled with the core. */
internal object SubmissionFailurePolicy {
    fun message(failure: Throwable, turnRequestDispatched: Boolean): String {
        val reason = failure.message ?: "请求失败"
        return when {
            !turnRequestDispatched -> "$reason；指令未提交，原文已保留，可重试"
            FirstTurnRecoveryPolicy.explicitlyRejected(failure) ->
                "$reason；内核已明确拒绝指令，原文已保留，可修正后重试"
            else ->
                "$reason；服务端可能已开始执行，原文已保留。" +
                    "请先核对会话记录和任务状态，再决定是否重试以避免重复执行"
        }
    }
}
