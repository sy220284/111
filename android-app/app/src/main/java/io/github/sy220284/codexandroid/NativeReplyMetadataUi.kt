package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun NativeReplyMetadata(vm: CodexViewModel, thread: String, items: List<TimelineItem>,
    canOpenThread: Boolean) {
    NativeSafetyReplyNotice(items)
    val records = remember(items) { items.flatMap(AsyncReplyContract::attributes) }
        .filterNot(AgentReplyContract::restricted)
    val citations = records.mapNotNull { it.optJSONObject("memoryCitation") }
    if (citations.isEmpty()) return
    var expanded by rememberSaveable(thread, items.first().id) { mutableStateOf(false) }
    val entries = citations.flatMap { citation ->
        val rows = citation.optJSONArray("entries")
        if (rows == null) emptyList() else (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }.distinctBy { it.toString() }
    val threads = citations.flatMap { citation ->
        val rows = citation.optJSONArray("threadIds")
        if (rows == null) emptyList() else (0 until rows.length()).map { rows.optString(it) }.filter(String::isNotBlank)
    }.distinct()
    if (entries.isEmpty() && threads.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("reply-memory-citations")) {
        CodexSecondaryButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "收起记忆引用" else "查看记忆引用（" + entries.size + "）")
        }
        CodexReveal(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry ->
                    Text(entry.optString("path") + " · " + entry.optInt("lineStart") + "–" + entry.optInt("lineEnd"),
                        style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    entry.optString("note").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                threads.filterNot { it == thread }.forEach { id ->
                    CodexSecondaryButton(onClick = { vm.chooseThread(id) }, enabled = canOpenThread,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("打开来源会话 " + id.take(12)) }
                }
            }
        }
    }
}

/** User-triggered, scoped MCP resource inspection. HTML/scripts never execute in chat. */
@Composable
internal fun NativeMcpResourceCard(vm: CodexViewModel, thread: String, item: TimelineItem,
    enabled: Boolean) {
    val raw = item.metadata() ?: return
    if (item.kind != "mcpToolCall" || item.status != "completed") return
    val uri = raw.optJSONObject("mcpAppUi")?.optString("resourceUri")?.takeIf(String::isNotBlank)
        ?: raw.optString("mcpAppResourceUri").takeIf(String::isNotBlank) ?: return
    val server = raw.optString("server").takeIf(String::isNotBlank) ?: return
    val scope = rememberCoroutineScope()
    var loading by remember(thread, item.id) { mutableStateOf(false) }
    var content by remember(thread, item.id) { mutableStateOf<String?>(null) }
    var app by remember(thread, item.id) { mutableStateOf<Triple<String, Long, Long>?>(null) }
    var error by remember(thread, item.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().testTag("reply-mcp-resource")) {
        CodexSecondaryButton(onClick = {
            if (loading || vm.activeThread.value != thread) return@CodexSecondaryButton
            val session = vm.bridge.connectedSession.value ?: return@CodexSecondaryButton
            val revision = vm.catalogWarmup.accountRevision.value
            loading = true
            error = null
            scope.launch {
                try {
                    val params = JSONObject().put("threadId", thread).put("originCallId", item.id)
                        .put("server", server).put("uri", uri)
                    if (!raw.isNull("environmentId")) params.put("environmentId", raw.get("environmentId"))
                    val result = vm.bridge.request("mcpServer/resource/read", params, expectedSession = session,
                        sendPermit = { vm.activeThread.value == thread && vm.catalogWarmup.accountRevision.value == revision })
                    check(vm.activeThread.value == thread && vm.bridge.connectedSession.value == session &&
                        vm.catalogWarmup.accountRevision.value == revision) { "会话已变化" }
                    val origin = result.optString("originCallId").takeUnless { result.isNull("originCallId") }
                    check(origin == null || origin == item.id) { "工具资源归属不一致" }
                    val html = McpAppHostContract.html(result, uri)
                    if (html != null) { app = Triple(html, session, revision); return@launch }
                    val values = result.getJSONArray("contents")
                    val text = (0 until values.length()).joinToString("\n\n") {
                        val value = values.getJSONObject(it)
                        val label = value.optString("mimeType").ifBlank { "资源" }
                        label + "\n" + if (value.has("text")) value.getString("text") else "二进制资源，当前仅支持文本预览。"
                    }
                    require(text.length <= 131072) { "工具资源超过当前文本预览容量" }
                    content = text
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "无法读取工具资源" }
                finally { loading = false }
            }
        }, enabled = enabled && !loading, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (loading) "正在读取资源" else "打开应用 / 工具资源")
        }
        error?.let { ErrorText(it) }
    }
    app?.let { (html, epoch, revision) -> NativeMcpAppHost(vm, thread, raw, html, epoch, revision) { app = null } }
    content?.let { FullTextReaderDialog("工具资源（文本预览）", it) { content = null } }
}
