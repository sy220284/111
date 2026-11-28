package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Joined presentation lists retain their historical list instance. A stream
 * update does not copy it, and equality skips it when ownership is unchanged. */
internal class TimelineJoinedList<T>(val history: List<T>, val active: List<T>) : AbstractList<T>() {
    override val size: Int get() = history.size + active.size
    override fun get(index: Int): T {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Presentation index $index")
        return if (index < history.size) history[index] else active[index - history.size]
    }
    override fun equals(other: Any?): Boolean = this === other ||
        if (other is TimelineJoinedList<*>) {
            size == other.size && if (history.size == other.history.size)
                (history === other.history || history == other.history) && active == other.active
            else super.equals(other)
        } else super.equals(other)
    override fun hashCode(): Int = super.hashCode()
}

internal data class TimelinePresentationPart(
    val rows: List<ProgressiveReplyProjection.Row>,
    val displayRows: TimelineDisplayRows,
    val answeredQuestions: Set<String>,
    val formalTurns: Set<String>
)

/** One immutable presentation snapshot: history and live content are independent
 * projections of the same authoritative timeline. No event or record is discarded. */
internal data class TimelinePresentation(
    val frame: ActiveConversationContext,
    val history: TimelinePresentationPart,
    val active: TimelinePresentationPart,
    private val indexed: IndexedTimeline,
    val tail: TimelineItem?
) {
    val rows: List<ProgressiveReplyProjection.Row> = TimelineJoinedList(history.rows, active.rows)
    val displayRows: List<TimelineDisplayRow> = TimelineJoinedList(history.displayRows, active.displayRows)
    fun answered(id: String) = id in history.answeredQuestions || id in active.answeredQuestions
    fun hasFormalTurn(id: String) = id in history.formalTurns || id in active.formalTurns
    val hasFormalReply: Boolean get() = history.formalTurns.isNotEmpty() || active.formalTurns.isNotEmpty()
    fun displayIndex(key: String): Int = history.displayRows.position(key)
        ?: active.displayRows.position(key)?.let { history.displayRows.size + it } ?: -1
    fun headingIndex(key: String, heading: Int): Int = history.displayRows.headingPosition(key, heading)
        ?: active.displayRows.headingPosition(key, heading)?.let { history.displayRows.size + it } ?: -1
    fun byId(id: String) = indexed.byId(id)
}

internal class TimelinePresentationEngine {
    private class PartEngine {
        private val replies = ProgressiveReplyProjection.Cache()
        private val pages = TimelineReplyPages()
        private var answeredCache = java.util.IdentityHashMap<TimelineItem, Set<String>>()
        fun project(groups: List<List<TimelineItem>>, running: Boolean, turn: String?,
            earlier: Boolean): TimelinePresentationPart {
            val rows = replies.rows(groups, running, turn, earlier)
            val answered = mutableSetOf<String>()
            val formal = mutableSetOf<String>()
            val nextCache = java.util.IdentityHashMap<TimelineItem, Set<String>>()
            for (group in groups) {
                if (group is TimelineGroupSnapshot && group.isActivity) continue
                for (item in group) {
                    if (item.kind == "user") {
                        val ids = answeredCache[item] ?: AsyncReplyContract.answeredFromInput(item.metadata()?.optJSONArray("content"))
                        nextCache[item] = ids
                        answered += ids
                    }
                    if (ProgressiveReplyProjection.answer(item)) formal += item.turnId
                }
            }
            answeredCache = nextCache
            return TimelinePresentationPart(rows, pages.project(rows), answered, formal)
        }
    }
    private val historyEngine = PartEngine()
    private val activeEngine = PartEngine()
    private data class History(val grouping: TimelineGrouping, val count: Int,
        val earlier: Boolean, val part: TimelinePresentationPart)
    private var history: History? = null

    fun project(frame: ActiveConversationContext, hasEarlier: Boolean): TimelinePresentation {
        val indexed = IndexedTimeline.from(frame.timeline)
        val grouping = indexed.grouping
        val groups = grouping.groups
        // Never split a native turn. If the running turn is not the last
        // contiguous section (late/steering events), keep the whole projection
        // in the live partition until the ownership boundary becomes clear.
        val boundary = activeBoundary(grouping, frame.running, frame.turnId)
        val cached = history?.takeIf { it.count == boundary && it.earlier == hasEarlier &&
            it.grouping.sameIdentityPrefix(grouping, boundary) }
        val prefix = cached?.part ?: historyEngine.project(groups.subList(0, boundary), false, null, hasEarlier)
        history = History(grouping, boundary, hasEarlier, prefix)
        val suffix = activeEngine.project(groups.subList(boundary, groups.size), frame.running,
            frame.turnId, hasEarlier && boundary == 0)
        return TimelinePresentation(frame, prefix, suffix, indexed,
            indexed.lastOrNull { it.kind != "turnOutcome" })
    }

    private fun activeBoundary(grouping: TimelineGrouping, running: Boolean, turn: String?): Int {
        val groups = grouping.groups
        if (groups.isEmpty()) return 0
        val last = groups.last().first().turnId
        if (running && (turn == "" || (!turn.isNullOrBlank() && turn != last))) return 0
        return grouping.lastTurnStart
    }
}

/** Only the completed presentation is observed by the viewport. The expensive
 * grouping, row and question projections are prepared by a serial worker. */
@Composable
internal fun collectTimelinePresentation(vm: CodexViewModel, thread: String?,
    hasEarlier: Boolean): State<TimelinePresentation?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val earlier by rememberUpdatedState(hasEarlier)
    val session = vm.bridge.connectedSession.collectAsStateWithLifecycle().value
    val account = vm.catalogWarmup.accountRevision.collectAsStateWithLifecycle().value
    return key(vm, thread, session, account) {
        produceCodexUiState<TimelinePresentation?>(null, vm, thread, session, account, lifecycle) {
            val engine = TimelinePresentationEngine()
            // Conflate immutable inputs; one frame gate after preparation avoids double cadence.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.conversationState, snapshotFlow { earlier }) { frame, older -> frame to older }
                    .conflate().collect { (snapshot, older) ->
                        if (snapshot.threadId != thread) value = null
                        else {
                            val epoch = vm.timelineEpoch
                            val prepared = withContext(Dispatchers.Default) { engine.project(snapshot, older) }
                            fun owns() = vm.conversationState.value.threadId == thread &&
                                vm.timelineEpoch == epoch && vm.bridge.connectedSession.value == session &&
                                vm.catalogWarmup.accountRevision.value == account
                            if (owns()) {
                                withFrameNanos { }
                                value = if (owns()) prepared else null
                            } else value = null
                        }
                }
            }
        }
    }
}
