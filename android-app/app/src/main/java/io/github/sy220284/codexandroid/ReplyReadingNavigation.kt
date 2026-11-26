package io.github.sy220284.codexandroid

import androidx.compose.runtime.staticCompositionLocalOf

internal data class ReplyHeadingEntry(val rowKey: String, val heading: Int, val title: String, val level: Int)
internal data class ReplyHeadingNavigation(val heading: Int, val onReached: () -> Unit)
internal val LocalReplyHeadingNavigation = staticCompositionLocalOf<ReplyHeadingNavigation?> { null }
internal val LocalReplyFileOpen = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal object ReplyReadingNavigation {
    fun outline(rows: List<ProgressiveReplyProjection.Row>): List<ReplyHeadingEntry> = rows.flatMap { row ->
        val item = row.group.singleOrNull()?.takeIf { ProgressiveReplyProjection.answer(it) } ?: return@flatMap emptyList()
        MessageMarkdown.blocks(item.text).filter { it.kind == "heading" }.mapIndexed { index, block ->
            ReplyHeadingEntry(row.key, index, block.text, block.level)
        }
    }
    fun replacementIndex(rows: List<ProgressiveReplyProjection.Row>, key: String): Int =
        rows.indexOfFirst { row -> row.key == key || row.group.any { item ->
            item.id == key || AsyncReplyContract.attributes(item).any { it.optString("id") == key }
        } }
}
