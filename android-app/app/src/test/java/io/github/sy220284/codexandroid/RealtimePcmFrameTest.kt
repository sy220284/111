package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class RealtimePcmFrameTest {
    @Test fun partialReadsPublishOnlyACompleteFrame() {
        val gate = VoiceUploadGate()
        val frame = RealtimePcmFrame(gate, 4)
        assertEquals(2, frame.read { bytes, offset, length ->
            assertEquals(0, offset)
            assertEquals(4, length)
            bytes[0] = 0x00
            bytes[1] = 0x20
            2
        })
        assertNull(frame.takeCompleteRevision())
        assertEquals(0, frame.read { _, _, _ -> 0 })
        assertEquals(2, frame.read { bytes, offset, length ->
            assertEquals(2, offset)
            assertEquals(2, length)
            bytes[2] = 0x00
            bytes[3] = 0xe0.toByte()
            2
        })
        assertEquals(gate.revision, frame.takeCompleteRevision())
        assertEquals(0.75f, pcm16Level(frame.bytes), 0.001f)
        assertNull(frame.takeCompleteRevision())
    }

    @Test fun aMuteTransitionDuringTheReadCannotMixSamplesAcrossRevisions() {
        val gate = VoiceUploadGate()
        val frame = RealtimePcmFrame(gate, 4)
        frame.read { _, _, _ -> 2 }
        frame.read { _, offset, _ ->
            assertEquals(2, offset)
            gate.setMuted(true)
            gate.setMuted(false)
            2
        }
        assertNull(frame.takeCompleteRevision())
        frame.read { _, offset, length ->
            assertEquals(0, offset)
            length
        }
        assertEquals(gate.revision, frame.takeCompleteRevision())
    }

    @Test fun mutedAndRevokedSamplesAreNeverPublished() {
        val gate = VoiceUploadGate()
        val frame = RealtimePcmFrame(gate, 4)
        gate.setMuted(true)
        frame.read { _, _, length -> length }
        assertNull(frame.takeCompleteRevision())
        gate.setMuted(false)
        frame.read { _, _, length -> length }
        gate.invalidate()
        assertNull(frame.takeCompleteRevision())
    }
}
