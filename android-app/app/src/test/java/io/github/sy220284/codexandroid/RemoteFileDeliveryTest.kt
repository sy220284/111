package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RemoteFileDeliveryTest {
    @Test fun pathsStayWithinRemoteBinding() {
        val binding = RemoteExecutionBinding("work", "ws://127.0.0.1:8080", "/home/user/work")
        assertEquals("out/a.pdf", RemoteFileDelivery.selection(binding, "out/a.pdf").relative)
        assertEquals("a.pdf", RemoteFileDelivery.selection(binding, "/home/user/work/out/a.pdf").filename)
        for (path in listOf("/home/user/work2/a", "/etc/passwd", "../a", "out/../a", "out//a", "a\\b"))
            assertTrue(path, runCatching { RemoteFileDelivery.selection(binding, path) }.isFailure)
    }
}
