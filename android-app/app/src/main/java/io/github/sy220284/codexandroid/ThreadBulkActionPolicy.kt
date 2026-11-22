package io.github.sy220284.codexandroid

/** Explicit allowed operations so the batch endpoint can never execute arbitrary RPC names. */
internal object ThreadBulkActionPolicy {
    fun method(kind: String): String = when (kind) {
        "archive" -> "thread/archive"
        "unarchive" -> "thread/unarchive"
        "delete" -> "thread/delete"
        else -> throw IllegalArgumentException("不支持的会话整理方式")
    }

    fun display(kind: String): String = when (kind) {
        "archive" -> "归档"
        "unarchive" -> "恢复"
        "delete" -> "删除"
        else -> throw IllegalArgumentException("不支持的会话整理方式")
    }

    fun uniqueIds(ids: List<String>): List<String> =
        ids.map(String::trim).filter(String::isNotEmpty).distinct()
}
