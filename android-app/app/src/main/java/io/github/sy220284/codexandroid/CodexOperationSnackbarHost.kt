package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Presentation only: native host continues to own receipt lifetime and exact undo action. */
@Composable
internal fun CodexOperationSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(hostState = state) { data ->
        Snackbar(
            snackbarData = data,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = Ink.parchment,
            contentColor = Ink.canvas,
            actionColor = Ink.canvas,
            dismissActionContentColor = Ink.canvas
        )
    }
}
