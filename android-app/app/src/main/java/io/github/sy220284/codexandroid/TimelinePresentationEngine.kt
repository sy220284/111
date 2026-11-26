package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/** One immutable presentation snapshot: the rows, metadata and running state
 * always correspond to the same authoritative timeline. Projection never mutates it. */
internal data class TimelinePresentation(
    val frame: ActiveConversationContext,
    val rows: List<ProgressiveReplyProjection.Row>,
    val answeredQuestions: Set<String>
)

internal class TimelinePresentationEngine {
    private val replies = ProgressiveReplyProjection.Cache()
    fun project(frame: ActiveConversationContext, hasEarlier: Boolean): TimelinePresentation =
        TimelinePresentation(frame,
            replies.rows(TimelineEventReducer.groups(frame.timeline), frame.running, frame.turnId, hasEarlier),
            AsyncReplyContract.answeredFromTimeline(frame.timeline))
}

/** Only the completed presentation is observed by the viewport. The expensive
 * grouping, row and question projections are prepared by a serial worker. */
@Composable
internal fun collectTimelinePresentation(vm: CodexViewModel, thread: String?,
    hasEarlier: Boolean): State<TimelinePresentation?> {
    val frame = vm.conversationState.collectFrameState(thread)
    val earlier by rememberUpdatedState(hasEarlier)
    return key(vm, thread) {
        produceCodexUiState<TimelinePresentation?>(null, vm, thread) {
            val engine = TimelinePresentationEngine()
            snapshotFlow { frame.value to earlier }.conflate().collect { (snapshot, older) ->
                if (snapshot.threadId != thread) value = null
                else {
                    val prepared = withContext(Dispatchers.Default) { engine.project(snapshot, older) }
                    // Do not present a previous owner if selection changed during preparation.
                    if (vm.conversationState.value.threadId == thread) {
                        withFrameNanos { }
                        if (vm.conversationState.value.threadId == thread) value = prepared
                    } else value = null
                }
            }
        }
    }
}
