package io.github.sy220284.codexandroid

/**
 * Only a server-side turn/start rejection is unambiguous.
 * Timeouts and connection failures can mean the turn actually started.
 */
internal object FirstTurnRecoveryPolicy {
    fun explicitlyRejected(failure: Throwable): Boolean =
        failure is RpcServerRejection && failure.method == "turn/start"
}
