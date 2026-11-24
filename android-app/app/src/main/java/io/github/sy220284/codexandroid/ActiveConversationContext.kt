package io.github.sy220284.codexandroid

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One authoritative selected conversation snapshot. The header does not observe text deltas. */
data class ActiveConversationContext(
    val workspace: String,
    val projectId: String? = null,
    val threadId: String? = null,
    val turnId: String? = null,
    val running: Boolean = false,
    val timeline: List<TimelineItem> = emptyList(),
    val diff: String = "",
    val modelProvider: String? = null
) {
    val header get() = ConversationHeader(workspace, threadId, turnId, running, diff)
}
data class ConversationHeader(val workspace: String, val threadId: String?, val turnId: String?,
    val running: Boolean, val diff: String)

/** Narrow observation of the same source, with synchronous reads and atomic updates. */
class ConversationField<T> internal constructor(
    private val source: MutableStateFlow<ActiveConversationContext>,
    private val read: (ActiveConversationContext) -> T,
    private val write: (ActiveConversationContext, T) -> ActiveConversationContext
) : StateFlow<T> {
    override var value: T
        get() = read(source.value)
        set(value) {
            while (true) {
                val before = source.value
                if (source.compareAndSet(before, write(before, value))) return
            }
        }
    override val replayCache: List<T> get() = listOf(value)
    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        source.map(read).distinctUntilChanged().collect(collector)
        error("Conversation state flow completed")
    }
}
