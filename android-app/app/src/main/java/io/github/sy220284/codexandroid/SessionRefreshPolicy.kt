package io.github.sy220284.codexandroid

/** Local app-server notifications and successful mutations determine refreshes. */
internal object SessionRefreshPolicy {
    private val threadMutations = setOf(
        "thread/name/set", "thread/archive", "thread/unarchive",
        "thread/delete", "thread/fork", "thread/section/move",
        "threadSection/update", "threadSection/delete"
    )

    fun affectsThreads(method: String): Boolean = method in threadMutations
}
