package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaAnalysisAdapterTest {
    private val target = MediaAnalysisTarget("thread", "provider", "model", "a".repeat(64), "turn", 1, setOf("text", "image", "audio"))
    @Test fun nativeMediaRequestBindsModelAndCredential() {
        val media = JSONArray().put(MediaAnalysisPlan.local("image", "/private/image.jpg"))
            .put(MediaAnalysisPlan.local("audio", "/private/audio.wav"))
        val request = MediaSampleWire.request(target, "描述媒体", media)
        assertEquals("thread", request.getString("threadId"))
        assertEquals("model", request.getString("model"))
        assertEquals("turn", request.getString("expectedTurnId"))
        assertEquals(target.credentialScope, request.getString("mediaScope"))
        assertTrue(request.getBoolean("interruptOnCancel"))
        assertTrue(request.getBoolean("disableMcp"))
        assertEquals("localAudio", request.getJSONArray("media").getJSONObject(1).getString("type"))
        assertFalse(request.toString().contains("baseUrl"))
    }
    @Test fun rejectsNonMediaPayloadsAndRemoteVideo() {
        for (kind in listOf("video_url", "skill", "image", "text")) {
            assertThrows(IllegalArgumentException::class.java) {
                MediaSampleWire.request(target, "分析", JSONArray().put(JSONObject().put("type", kind)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaSampleWire.request(target, "分析", JSONArray()) }
    }
    @Test fun completedTextMustBelongToBoundTurn() {
        val response = JSONObject().put("sourceTurnId", "turn").put("responseId", "result").put("text", "画面")
        assertEquals("画面", MediaSampleWire.result(response, target))
        assertThrows(IllegalArgumentException::class.java) {
            MediaSampleWire.result(response.put("sourceTurnId", "other"), target)
        }
        assertThrows(IllegalStateException::class.java) {
            MediaSampleWire.result(response.put("sourceTurnId", "turn").put("text", JSONObject.NULL), target)
        }
    }
    @Test fun credentialsProviderAndModelHaveSeparateCacheScopes() {
        assertNotEquals(target.checkpointScope, target.copy(provider = "other").checkpointScope)
        assertNotEquals(target.checkpointScope, target.copy(model = "other").checkpointScope)
        assertNotEquals(target.checkpointScope, target.copy(credentialScope = "b".repeat(64)).checkpointScope)
        assertEquals(target.checkpointScope, target.copy(epoch = 2).checkpointScope)
    }
    @Test fun videoOptionsDependOnActualAudioTrack() {
        assertEquals(listOf(MediaAnalysisMode.Understand), MediaAnalysisPlan.modes("video", false))
        assertEquals(listOf(MediaAnalysisMode.Understand, MediaAnalysisMode.VisualOnly, MediaAnalysisMode.Transcription),
            MediaAnalysisPlan.modes("video", true))
        assertEquals(listOf(MediaAnalysisMode.Understand, MediaAnalysisMode.Transcription), MediaAnalysisPlan.modes("audio", true))
    }
    @Test fun checkpointRetainsSourceTurnAndAppliedResults() {
        val original = AnalysisCheckpoint("thread", target.checkpointScope, "source", "Understand", "question",
            listOf("已完成"), 1, 1, sourceTurn = "turn")
        val restored = AnalysisCheckpoint.decode(original.encode())
        assertEquals(original, restored)
        assertTrue(restored.complete)
        assertEquals(1, restored.applied)
    }
    @Test fun preflightRejectsWholeVideoBeforeAnyUnsupportedAudioSegment() {
        val visual = target.copy(inputModalities = setOf("text", "image"))
        val whole = MediaAnalysisPlan.requiredModalities("video", true, MediaAnalysisMode.Understand)
        assertEquals(setOf("image", "audio"), whole)
        assertThrows(IllegalArgumentException::class.java) { visual.requireModalities(whole) }
        visual.requireModalities(MediaAnalysisPlan.requiredModalities("video", true, MediaAnalysisMode.VisualOnly))
        assertThrows(IllegalArgumentException::class.java) {
            visual.requireModalities(MediaAnalysisPlan.requiredModalities("video", true, MediaAnalysisMode.Transcription))
        }
        assertEquals(setOf("image"), MediaAnalysisPlan.requiredModalities("video", false, MediaAnalysisMode.Understand))
    }
}
