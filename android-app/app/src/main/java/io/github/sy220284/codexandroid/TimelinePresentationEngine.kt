package io.github.sy220284.codexandroid

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
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
    val earlier by rememberUpdatedState(hasEarlier)
    val session = vm.bridge.connectedSession.collectAsState().value
    val account = vm.catalogWarmup.accountRevision.collectAsState().value
    return key(vm, thread, session, account) {
        produceCodexUiState<TimelinePresentation?>(null, vm, thread, session, account) {
            val engine = TimelinePresentationEngine()
            // Conflate immutable inputs; one frame gate after preparation avoids double cadence.
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
