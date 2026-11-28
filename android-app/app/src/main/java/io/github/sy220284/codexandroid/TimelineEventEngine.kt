package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update

/**
 * Per-thread ordered workers prepare immutable timeline snapshots on Default.
 * Commit stays on Main, checking the original core session, timeline ownership
 * and snapshot identity. Selected and offscreen tasks use the same engine.
 */
internal object TimelineEventEngine {
    suspend fun applyEvent(vm: CodexViewModel, event: CoreNotification, completedTurn: String? = null): Boolean {
        val thread = event.params.getString("threadId")
        fun canFinish(state: ActiveConversationContext) = completedTurn != null &&
            state.threadId == thread && (state.turnId.isNullOrBlank() || state.turnId == completedTurn) &&
            vm.threadExecutions.value[thread] == null
        fun visible() = vm.activeThread.value == thread && vm.hydratingThread != thread
        while (vm.bridge.isCurrentEvent(event.wire)) {
            val selected = visible()
            val before = if (selected) vm.timeline.value else vm.liveTimelines.snapshot(thread)
            val epoch = vm.timelineEpoch
            val next = try {
                withContext(Dispatchers.Default) {
                    TimelineEventReducer.reduce(event.method, event.params, before.orEmpty(), event.mergedDelta)
                }
            } catch (failure: InvalidNativeSummaryIndex) {
                if (vm.bridge.isCurrentEvent(event.wire))
                    vm.errors.value = vm.errors.value + ("protocol" to (failure.message ?: "摘要分段协议错误"))
                // Keep consuming events. A completed item supplies the authoritative full summary.
                return false
            }
            if (!vm.bridge.isCurrentEvent(event.wire)) return false
            if (selected != visible()) continue
            if (selected) {
                if (vm.timelineEpoch != epoch || vm.timeline.value !== before) continue
                // No suspension between ownership validation and publication.
                vm.conversationState.update { state ->
                    if (canFinish(state)) state.copy(timeline = next, running = false, turnId = null)
                    else if (next !== before) state.copy(timeline = next) else state
                }
                return true
            }
            if (vm.liveTimelines.publish(thread, before, next, vm.threadExecutions.value.keys)) {
                // Hydration will merge the event into the selected history. Its header
                // may still belong to this turn, but a newer turn must remain running.
                vm.conversationState.update { state ->
                    if (canFinish(state)) state.copy(running = false, turnId = null) else state
                }
                return false
            }
        }
        return false
    }
}
