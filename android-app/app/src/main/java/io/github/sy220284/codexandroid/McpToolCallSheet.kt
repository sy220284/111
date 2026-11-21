package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** 真实 MCP 工具调用：先确认精确目标、参数和会话，响应按原始内容展示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolCallSheet(
    vm: CodexViewModel, server: String, tool: String,
    metadata: JSONObject, onDismiss: () -> Unit,
    onOpenThreads: () -> Unit
) {
    val thread by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val key = "mcpCall:" + server + ":" + tool
    var argsText by remember(server, tool) { mutableStateOf("{}") }
    var confirming by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    val args = remember(argsText) { runCatching { JSONObject(argsText) }.getOrNull() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp)
            .verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("调用 MCP 工具", style = MaterialTheme.typography.headlineSmall)
            Text(server + " / " + tool, style = MaterialTheme.typography.titleMedium)
            metadata.optString("description").takeIf { it.isNotBlank() }?.let {
                Text(it, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            }
            if (thread == null) {
                RequiresThreadPanel("MCP 工具调用", onOpenThreads)
            } else {
                Text("会话：" + thread.orEmpty().take(24), color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
                metadata.optJSONObject("inputSchema")?.let { schema ->
                    Text("工具参数结构", style = MaterialTheme.typography.titleMedium)
                    Text(schema.toString(2).take(3000), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                }
                OutlinedTextField(argsText, { argsText = it },
                    label = { Text("JSON 参数对象") }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3, maxLines = 8)
                if (args == null) Text("参数必须是有效的 JSON 对象", color = Ink.red)
                OutlinedButton(onClick = { confirming = true },
                    enabled = args != null && key !in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("核对并调用工具")
                }
            }
            if (key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[key]?.let { ErrorText(it) }
            data[key]?.let { response ->
                val isError = response.optBoolean("isError")
                CodexNotice(
                    if (isError) "工具返回了错误结果，请检查原始响应。" else "工具已返回结果。",
                    if (isError) NoticeTone.Warning else NoticeTone.Success
                )
                OutlinedButton(onClick = { showRaw = true },
                    modifier = Modifier.fillMaxWidth()) { Text("查看完整工具响应") }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (confirming && thread != null && args != null) {
        CodexConfirmDialog(
            title = "确认调用 MCP 工具",
            message = "将把指定参数交给已连接的 MCP 服务执行。操作可能修改服务端数据，请核对工具用途和参数。",
            detail = "服务：$server\n工具：$tool\n会话：$thread\n参数：$argsText",
            confirmLabel = "确认调用",
            onConfirm = {
                vm.rpc(key, "mcpServer/tool/call", JSONObject()
                    .put("server", server).put("tool", tool)
                    .put("threadId", thread).put("arguments", args))
                confirming = false
            }, onDismiss = { confirming = false }
        )
    }
    if (showRaw) data[key]?.let {
        FullTextReaderDialog("MCP 工具响应", it.toString(2)) { showRaw = false }
    }
}
