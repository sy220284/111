package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Read a single explicitly selected MCP resource via the official app-server. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpResourceSheet(
    serverName: String,
    resource: JSONObject,
    vm: CodexViewModel,
    onDismiss: () -> Unit
) {
    val uri = resource.optString("uri")
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val key = "mcpResource:" + serverName + ":" + uri
    val payload = data[key]
    var showRaw by remember(uri) { mutableStateOf(false) }
    var fullText by remember(uri) { mutableStateOf<String?>(null) }
    var fullTitle by remember(uri) { mutableStateOf("") }

    LaunchedEffect(serverName, uri) {
        if (uri.isNotBlank() && serverName.isNotBlank()) {
            vm.refresh(key, "mcpServer/resource/read",
                InspectionContracts.mcpResource(serverName, uri))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed,
        contentColor = Ink.parchment
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            SectionIntro("MCP / RESOURCE", resource.optString("name").ifBlank { "服务资源" },
                "从所选 MCP 服务读取资源内容，展示真实响应。")
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Hub, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Text(serverName, style = MaterialTheme.typography.titleMedium)
                }
                SelectionContainer {
                    Text(uri, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                }
                resource.optString("description").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                }
                OutlinedButton(
                    onClick = {
                        vm.refresh(key, "mcpServer/resource/read",
                            InspectionContracts.mcpResource(serverName, uri))
                    },
                    enabled = uri.isNotBlank() && key !in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(7.dp))
                    Text("重新读取资源")
                }
            }
            if (key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[key]?.let { ErrorText(it) }
            val entries = payload?.optJSONArray("contents")
            if (payload != null && (entries == null || entries.length() == 0)) {
                CodexEmptyState("资源没有可展示内容",
                    "服务端已响应，但未提供资源文本或附件记录。")
            }
            if (entries != null) {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val mimeType = entry.optString("mimeType").ifBlank { "未标明内容类型" }
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, tint = Ink.copper)
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text("资源片段 " + (index + 1),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(mimeType, color = Ink.muted,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val contentText = entry.optString("text")
                        if (contentText.isNotEmpty()) {
                            val shown = contentText.take(16000)
                            SelectionContainer {
                                Text(shown, fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink.parchment)
                            }
                            if (contentText.length > shown.length) {
                                OutlinedButton(onClick = {
                                    fullTitle = "资源片段 " + (index + 1)
                                    fullText = contentText
                                }) { Text("打开全文阅读器（${contentText.length} 字符）") }
                            }
                        } else {
                            Text("该片段为非文本资源，可在原始记录中核对元数据。",
                                color = Ink.muted)
                        }
                    }
                }
            }
            if (payload != null) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(if (showRaw) "收起原始响应" else "查看原始响应")
                }
                CodexReveal(showRaw) {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        val raw = payload.toString(2)
                        SelectionContainer {
                            Text(raw.take(16000), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                        }
                        if (raw.length > 16000) {
                            OutlinedButton(onClick = {
                                fullTitle = "原始 MCP 响应"
                                fullText = raw
                            }) { Text("阅读完整响应") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    fullText?.let { body ->
        FullTextReaderDialog(title = fullTitle, text = body,
            onDismiss = { fullText = null })
    }
}
