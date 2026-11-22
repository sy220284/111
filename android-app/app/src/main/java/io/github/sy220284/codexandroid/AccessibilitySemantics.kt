package io.github.sy220284.codexandroid

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Screen reader guidance stays on the disabled control instead of hidden text. */
internal fun Modifier.explainDisabled(disabled: Boolean, reason: String): Modifier =
    if (disabled) this.semantics { stateDescription = reason } else this
