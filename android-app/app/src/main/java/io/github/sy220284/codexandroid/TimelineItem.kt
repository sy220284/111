package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Immutable native message snapshot; streaming output remains chunked until read. */
class TimelineItem private constructor(
    val id: String, val kind: String, private val staticText: String,
    val status: String, private val staticEvidence: String, val turnId: String,
    val phase: String, private val stream: TimelineNativeStream?
) {
    constructor(id: String, kind: String, text: String, status: String = "",
        evidence: String = "", turnId: String = "", phase: String = "") :
        this(id, kind, text, status, evidence, turnId, phase, null)

    val text: String get() = stream?.text ?: staticText
    val evidence: String get() = stream?.evidence ?: staticEvidence
    internal val hasEvidence: Boolean get() = stream != null || staticEvidence.isNotBlank()
    internal fun metadata(): JSONObject? = stream?.metadata ?: if (staticEvidence.isBlank()) null
        else try { JSONObject(staticEvidence) } catch (_: org.json.JSONException) { null }
    internal fun commandOutputTail(limit: Int): String =
        if (stream?.field == "aggregatedOutput") stream.outputTail(limit)
        else metadata()?.optString("aggregatedOutput").orEmpty().takeLast(limit)

    internal fun appendNative(field: String, delta: String, index: Int, lifecycle: String): TimelineItem {
        val source = stream?.takeIf { it.field == field } ?: TimelineNativeStream.from(this, field)
        val next = source.append(delta, index)
        val nativeStatus = source.metadata.optString("status").takeUnless { source.metadata.isNull("status") }.orEmpty()
        return TimelineItem(id, kind, "", nativeStatus.ifBlank { lifecycle }, "", turnId, phase, next)
    }
    internal fun withStatus(status: String) =
        TimelineItem(id, kind, staticText, status, staticEvidence, turnId, phase, stream)

    fun copy(id: String = this.id, kind: String = this.kind, text: String = this.text,
        status: String = this.status, evidence: String = this.evidence, turnId: String = this.turnId,
        phase: String = this.phase): TimelineItem = TimelineItem(id, kind, text, status, evidence, turnId, phase)
    operator fun component1() = id
    operator fun component2() = kind
    operator fun component3() = text
    operator fun component4() = status
    operator fun component5() = evidence
    operator fun component6() = turnId
    operator fun component7() = phase

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimelineItem || id != other.id || kind != other.kind ||
            status != other.status || turnId != other.turnId || phase != other.phase) return false
        if (stream != null && other.stream != null && stream.definitelyDifferent(other.stream)) return false
        return text == other.text && evidence == other.evidence
    }
    override fun hashCode(): Int {
        var hash = id.hashCode()
        for (value in listOf(kind, text, status, evidence, turnId, phase)) hash = 31 * hash + value.hashCode()
        return hash
    }
    override fun toString() = "TimelineItem(id=$id, kind=$kind, status=$status, turnId=$turnId, phase=$phase)"
}
