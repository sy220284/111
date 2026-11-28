package io.github.sy220284.codexandroid

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/** Snackbar presents only a still-valid receipt; the controller validates it again on click. */
internal suspend fun presentActionFeedback(host: SnackbarHostState, receipt: ActionFeedbackReceipt,
    isCurrent: (ThreadUndoOffer) -> Boolean, onUndo: (ThreadUndoOffer) -> Unit) {
    val undo = receipt.undo?.takeIf(isCurrent)
    val result = host.showSnackbar(
        message = receipt.message,
        actionLabel = if (undo != null) "撤销" else null,
        withDismissAction = receipt.isError || undo != null,
        duration = if (receipt.isError || undo != null) SnackbarDuration.Long else SnackbarDuration.Short
    )
    if (result == SnackbarResult.ActionPerformed && undo != null) onUndo(undo)
}
