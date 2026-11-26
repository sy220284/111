package io.github.sy220284.codexandroid

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The notification collector serializes protocol events. The expensive, pure
 * delta reduction runs on Default; ownership and publication remain on Main.
 * UI operations can mutate the selected timeline while computation is in flight:
 * retry against its latest immutable snapshot, or hand the event to the offscreen
 * reconciliation store. Never commit a result from a retired core session.
 */
internal object TimelineEventEngine {
    suspend fun applyVisibleDelta(vm: CodexViewModel, event: CoreNotification): Boolean {
        val thread = event.params.getString("threadId")
        while (vm.bridge.isCurrentEvent(event.wire)) {
            if (vm.activeThread.value != thread || vm.hydratingThread == thread) {
                vm.liveTimelines.event(thread, event.method, event.params,
                    vm.threadExecutions.value.keys, event.mergedDelta)
                return false
            }
            val before = vm.timeline.value
            val epoch = vm.timelineEpoch
            val next = withContext(Dispatchers.Default) {
                TimelineEventReducer.reduce(event.method, event.params, before, event.mergedDelta)
            }
            if (!vm.bridge.isCurrentEvent(event.wire)) return false
            if (vm.activeThread.value != thread || vm.hydratingThread == thread ||
                vm.timelineEpoch != epoch || vm.timeline.value !== before) continue
            // No suspension between ownership validation and publication. Existing
            // UI writers remain on Main, preserving concurrent pending items.
            if (next !== before) vm.timeline.value = next
            return true
        }
        return false
    }
}
