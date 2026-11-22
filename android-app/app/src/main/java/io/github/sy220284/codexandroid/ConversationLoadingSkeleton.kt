package io.github.sy220284.codexandroid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Static placeholders avoid flashing previous-thread content during resume. */
@Composable
internal fun ConversationLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier.semantics { contentDescription = "正在恢复会话内容" }
            .padding(horizontal = 21.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("正在恢复会话…", color = Ink.muted,
            style = MaterialTheme.typography.bodyMedium)
        repeat(3) { index ->
            Column(
                Modifier.fillMaxWidth(if (index == 1) .73f else .9f)
                    .align(if (index == 1) Alignment.End else Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Spacer(
                    Modifier.fillMaxWidth().height(42.dp)
                        .background(Ink.border.copy(alpha = .24f),
                            RoundedCornerShape(17.dp))
                )
                Spacer(
                    Modifier.fillMaxWidth(.65f).height(12.dp)
                        .background(Ink.border.copy(alpha = .22f),
                            RoundedCornerShape(8.dp))
                )
            }
        }
    }
}
