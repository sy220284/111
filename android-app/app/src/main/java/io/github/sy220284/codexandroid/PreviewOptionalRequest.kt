package io.github.sy220284.codexandroid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Prepare an optional presentation capability without changing the primary request's result or retry semantics. */
internal object PreviewOptionalRequest {
    suspend fun <P, R> execute(
        original: P,
        prepare: suspend () -> P,
        dispatch: suspend (P) -> R,
        unavailable: (R, Exception) -> Unit
    ): R {
        var failure: Exception? = null
        val outgoing = try {
            prepare()
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = timeout
            original
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            original
        }
        currentCoroutineContext().ensureActive()
        val receipt = dispatch(outgoing)
        failure?.let { unavailable(receipt, it) }
        return receipt
    }
}
