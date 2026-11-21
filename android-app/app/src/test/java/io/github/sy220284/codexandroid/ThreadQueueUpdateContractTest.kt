package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class ThreadQueueUpdateContractTest {
    @Test fun updatesExistingQueueItemUsingOfficialV2FieldNames() {
        val args = ThreadQueueUpdateContract.build("thread-a", "queue-b", "  修复登录错误  ")
        assertEquals("thread-a", args.getString("threadId"))
        assertEquals("queue-b", args.getString("queuedSubmissionId"))
        assertEquals(1, args.getJSONArray("input").length())
        val text = args.getJSONArray("input").getJSONObject(0)
        assertEquals("text", text.getString("type"))
        assertEquals("修复登录错误", text.getString("text"))
        assertFalse(args.has("responsesapiClientMetadata"))
    }

    @Test fun missingIdsAndEmptyEditAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ThreadQueueUpdateContract.build("", "queue-b", "内容")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadQueueUpdateContract.build("thread-a", " ", "内容")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThreadQueueUpdateContract.build("thread-a", "queue-b", " ")
        }
    }

    @Test fun imageOrMultiPartQueueInputsAreNotSilentlyConvertedToText() {
        val oneText = org.json.JSONArray().put(
            org.json.JSONObject().put("type", "text").put("text", "内容"))
        val image = org.json.JSONArray().put(
            org.json.JSONObject().put("type", "localImage").put("path", "/tmp/photo.png"))
        assertTrue(ThreadQueueUpdateContract.canEditAsText(oneText))
        assertFalse(ThreadQueueUpdateContract.canEditAsText(image))
        assertFalse(ThreadQueueUpdateContract.canEditAsText(
            org.json.JSONArray().put(oneText.getJSONObject(0)).put(oneText.getJSONObject(0))))
    }
}
