package io.github.sy220284.codexandroid

/** 输入中的查询和实际提交的查询保持分离，避免旧结果混入新查询。 */
internal object ThreadSearchSubmission {
    fun normalize(draft: String): String = draft.trim()

    fun matches(draft: String, submitted: String): Boolean =
        submitted.isNotBlank() && normalize(draft) == submitted

    fun canSubmit(draft: String, loading: Boolean): Boolean =
        normalize(draft).isNotEmpty() && !loading
}
