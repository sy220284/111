package io.github.sy220284.codexandroid

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteExecutionBindingTest {
    @Test fun canonicalUrisBecomeNativePathsWithoutAndroidFileResolution() {
        assertEquals("/home/user/my project", RemoteExecutionBinding.nativePath("file:///home/user/my%20project"))
        assertEquals("C:\\Users\\user", RemoteExecutionBinding.nativePath("file:///C:/Users/user"))
        assertEquals("\\\\server\\share\\repo", RemoteExecutionBinding.nativePath("file://server/share/repo"))
        for (bad in listOf("https://host/repo", "file:///a/../b", "file:///a%00b", "file:///a?token=x")) {
            assertTrue(bad, runCatching { RemoteExecutionBinding.nativePath(bad) }.isFailure)
        }
    }

    @Test fun remoteBindingNeverAcceptsLocalOrDifferentWorkingDirectory() {
        val binding = RemoteExecutionBinding("work", "wss://example.org/exec", "/home/work")
        val params = binding.apply(JSONObject())
        assertTrue(binding.matches(params))
        assertTrue(RemoteExecutionBinding.hasRemote(params))
        assertFalse(binding.matches(JSONObject()))
        assertFalse(binding.matches(RemoteExecutionBinding("other", "wss://example.org/exec", "/home/work").apply(JSONObject())))
        assertFalse(binding.matches(RemoteExecutionBinding("work", "wss://example.org/exec", "/tmp").apply(JSONObject())))
        assertTrue(runCatching { RemoteExecutionBinding("work", "wss://example.org/exec?token=x", "/home/work") }.isFailure)
    }
}
