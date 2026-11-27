package io.github.sy220284.codexandroid

/** The full bounded session and its draft cursor share one immutable state. */
internal data class ScreenAnalysisResults(
    val observations: List<Observation> = emptyList(),
    val appliedCount: Int = 0
) {
    data class Observation(val startMs: Long, val endMs: Long, val text: String)

    init {
        require(observations.size <= MAX_REQUESTS && appliedCount in 0..observations.size)
    }

    val pending: List<Observation> get() = observations.drop(appliedCount)

    fun append(observation: Observation): ScreenAnalysisResults {
        require(observations.size < MAX_REQUESTS) { "本次屏幕分析已达到请求上限" }
        return copy(observations = observations + observation)
    }

    /** Only consume the reviewed snapshot, retaining anything completed afterwards. */
    fun appliedThrough(count: Int): ScreenAnalysisResults {
        require(count in appliedCount..observations.size)
        return copy(appliedCount = count)
    }

    companion object {
        const val MAX_REQUESTS = 12
    }
}
