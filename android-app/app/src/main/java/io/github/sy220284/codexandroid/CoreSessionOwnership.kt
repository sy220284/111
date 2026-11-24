package io.github.sy220284.codexandroid

/** The same monitor protects session identity, request admission and restart reservation. */
internal class CoreSessionOwnership {
    private var serial = 0L
    private var session: Long? = null
    private var ready = false
    private var restartReserved = false

    @Synchronized fun begin(): Long {
        check(session == null) { "旧内核尚未退出" }
        ready = false
        return (++serial).also { session = it }
    }
    @Synchronized fun current(): Long? = session
    @Synchronized fun owns(token: Long): Boolean = session == token
    @Synchronized fun acceptsUserRequest(): Boolean = ready && !restartReserved
    @Synchronized fun markReady(token: Long): Boolean {
        if (!owns(token)) return false
        ready = true
        restartReserved = false
        return true
    }
    @Synchronized fun reserveRestart(requests: Int, approvals: Int): Boolean {
        if (restartReserved || (session != null && !ready) || requests != 0 || approvals != 0) return false
        restartReserved = true
        return true
    }
    @Synchronized fun releaseRestart() { restartReserved = false }
    @Synchronized fun retire(token: Long): Long? {
        if (!owns(token)) return null
        session = null
        ready = false
        return ++serial
    }
    @Synchronized fun isRetired(token: Long): Boolean = session == null && serial == token
}
