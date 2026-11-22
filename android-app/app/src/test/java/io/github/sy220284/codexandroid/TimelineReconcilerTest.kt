package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineReconcilerTest {
    @Test fun identicalUserMessagesUsePendingMarkerOnly() {
        val items = listOf(
            TimelineItem("server-first", "user", "重复的指令"),
            TimelineItem("pending-uuid-2", "user", "重复的指令", "sending"),
            TimelineItem("assistant", "agent", "处理中")
        )
        assertEquals(1, TimelineReconciler.position(items, "server-second", "userMessage"))
        assertEquals(0, TimelineReconciler.position(items, "server-first", "userMessage"))
        assertEquals(-1, TimelineReconciler.position(items, "unseen-agent", "agentMessage"))
    }

    @Test fun olderAcceptedPendingIsNotMatchedAgain() {
        val items = listOf(
            TimelineItem("pending-old", "user", "任务", ""),
            TimelineItem("pending-new", "user", "任务", "sending")
        )
        assertEquals(1, TimelineReconciler.position(items, "actual-new", "userMessage"))
    }
}
