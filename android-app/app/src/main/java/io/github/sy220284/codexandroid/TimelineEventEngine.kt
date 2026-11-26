package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One serial protocol consumer prepares immutable timeline snapshots on Default.
 * Commit stays on Main, checking the original core session, timeline ownership
 * and snapshot identity. Selected and offscreen tasks use the same engine.
 */
internal object TimelineEventEngine {
    suspend fun applyDelta(vm: CodexViewModel, event: CoreNotification): Boolean {
        val thread = event.params.getString("threadId")
        fun visible() = vm.activeThread.value == thread && vm.hydratingThread != thread
        while (vm.bridge.isCurrentEvent(event.wire)) {
            val selected = visible()
            val before = if (selected) vm.timeline.value else vm.liveTimelines.snapshot(thread)
            val epoch = vm.timelineEpoch
            val next = withContext(Dispatchers.Default) {
                TimelineEventReducer.reduce(event.method, event.params, before.orEmpty(), event.mergedDelta)
            }
            if (!vm.bridge.isCurrentEvent(event.wire)) return false
            if (selected != visible()) continue
            if (selected) {
                if (vm.timelineEpoch != epoch || vm.timeline.value !== before) continue
                // No suspension between ownership validation and publication.
                if (next !== before) vm.timeline.value = next
                return true
            }
            if (vm.liveTimelines.publish(thread, before, next, vm.threadExecutions.value.keys)) return false
        }
        return false
    }
}
