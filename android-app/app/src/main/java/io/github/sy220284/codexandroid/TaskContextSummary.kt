package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Keep task purpose and actual outputs in one context strip above the reading area. */
@Composable
internal fun TaskContextSummary(goal: JSONObject?, changes: String?, busy: Boolean, navigate: (String) -> Unit) {
    if (goal == null && changes == null) return
    WorkSection(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), WorkSectionKind.Status) {
        if (goal != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-goal-banner")
                .clickable { navigate("goals") }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexIcon(CodexGlyph.Flag, null, tint = Ink.copper, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text("任务目标 · " + PresentationPolicy.goalStatus(goal.optString("status")),
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    Text(goal.optString("objective"), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                CodexIcon(CodexGlyph.ChevronRight, null, tint = Ink.muted)
            }
        }
        if (goal != null && changes != null) DividerLine()
        if (changes != null) {
            BoxWithConstraints(Modifier.fillMaxWidth().testTag("chat-changes-summary")) {
                val stacked = !CodexViewportPolicy.fitsActions(maxWidth.value, codexEffectiveFontScale(), 2, 132f)
                val summary: @Composable () -> Unit = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodexIcon(CodexGlyph.Diff, null, tint = Ink.copper, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("代码变更", style = MaterialTheme.typography.titleSmall)
                            Text(changes, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                        }
                    }
                }
                val actions: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        CodexTextAction(onClick = { navigate("diff") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("查看") }
                        CodexTextAction(onClick = { navigate("review") }, enabled = !busy,
                            contentPadding = PaddingValues(horizontal = 8.dp)) { Text("审查") }
                    }
                }
                if (stacked) Column {
                    summary()
                    Box(Modifier.align(Alignment.End)) { actions() }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { summary() }
                    actions()
                }
            }
        }
    }
}
