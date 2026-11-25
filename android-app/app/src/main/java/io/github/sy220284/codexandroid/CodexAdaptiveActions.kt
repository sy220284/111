package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep each action's identity and callback while changing only its arrangement. */
@Composable
internal fun CodexAdaptiveActions(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (CodexViewportPolicy.fitsActions(maxWidth.value, codexEffectiveFontScale(), 2, 132f, 12f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                first(Modifier.weight(1f).heightIn(min = 48.dp))
                second(Modifier.weight(1f).heightIn(min = 48.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth().heightIn(min = 48.dp))
                second(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
    }
}
