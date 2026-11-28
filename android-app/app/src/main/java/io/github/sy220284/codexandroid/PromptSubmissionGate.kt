package io.github.sy220284.codexandroid

/** One physical response per native request. Keep the claim until the writer
 * finishes, even if the UI coroutine is cancelled while the pipe is flushing. */
internal class PromptSubmissionGate {
    private val active = mutableSetOf<Pair<Long, Any>>()
    @Synchronized fun claim(epoch: Long, id: Any): Boolean = active.add(epoch to id)
    @Synchronized fun release(epoch: Long, id: Any) { active.remove(epoch to id) }
}
