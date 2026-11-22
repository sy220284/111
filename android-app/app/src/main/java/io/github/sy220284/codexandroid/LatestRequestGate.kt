package io.github.sy220284.codexandroid

/**
 * 每个界面结果键仅归最后发出的请求所有。
 * 较慢的旧请求完成时，不能覆盖新结果或提前关闭新请求的加载状态。
 * 由 ViewModel 主线程使用。
 */
internal class LatestRequestGate {
    private val active = mutableMapOf<String, Any>()

    fun begin(key: String): Any = Any().also { active[key] = it }

    fun isLatest(key: String, ticket: Any): Boolean = active[key] === ticket

    /** Release ownership when transient search entries leave the bounded cache. */
    fun cancel(key: String) {
        active.remove(key)
    }

    fun complete(key: String, ticket: Any): Boolean {
        if (!isLatest(key, ticket)) return false
        active.remove(key)
        return true
    }
}
