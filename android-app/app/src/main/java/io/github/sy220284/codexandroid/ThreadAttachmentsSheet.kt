package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** 与当前会话绑定的持久化资料：列出、增加文本备注、删除准确身份键。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadAttachmentsSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val active by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var noteKey by rememberSaveable(active) { mutableStateOf("") }
    var noteText by rememberSaveable(active) { mutableStateOf("") }
    var removeTarget by remember(active) { mutableStateOf<JSONObject?>(null) }
    var reader by remember(active) { mutableStateOf<String?>(null) }
    var page by remember(active) { mutableIntStateOf(0) }
    val baseKey = "threadAttachment:" + (active ?: "none") + ":"
    val all = (0..page).flatMap { records(data[baseKey + it]) }
        .distinctBy { it.optString("id").ifBlank {
            it.optString("attachmentType") + ":" + it.optString("identityKey")
        } }
    val currentKey = baseKey + page
    val cursor = data[currentKey]?.optString("nextCursor").orEmpty()
    fun refresh() {
        val threadId = active ?: return
        page = 0
        vm.refresh(baseKey + "0", "thread/attachment/list",
            JSONObject().put("threadId", threadId).put("limit", 50))
    }
    LaunchedEffect(active) { if (active != null) refresh() }
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = true,
        containerColor = Ink.canvas, contentColor = Ink.parchment
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            AtlasSheetHeader("会话关联资料", onDismiss)
            Text("资料保存在当前会话中；备注不会作为新指令发送。",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
            if (active == null) CodexEmptyState("请先打开会话", "会话资料属于具体任务。")
            else {
                LazyColumn(Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)) {
                    item { MaterialPanel(Modifier.fillMaxWidth()) {
                    Text("添加文本备注", style = MaterialTheme.typography.titleMedium)
                    CodexInputField(noteKey, { noteKey = it }, singleLine = true,
                        label = { Text("备注名称") }, modifier = Modifier.fillMaxWidth())
                    CodexInputField(noteText, { noteText = it }, minLines = 2, maxLines = 5,
                        label = { Text("备注内容") }, modifier = Modifier.fillMaxWidth())
                    CodexSecondaryButton(onClick = {
                        val originalKey = noteKey.trim()
                        val originalText = noteText.trim()
                        vm.rpc("threadAttachmentWrite", "thread/attachment/add",
                            JSONObject().put("threadId", active)
                                .put("attachmentType", "note")
                                .put("identityKey", originalKey)
                                .put("payload", JSONObject().put("text", originalText)),
                            after = {
                                if (noteKey.trim() == originalKey) noteKey = ""
                                if (noteText.trim() == originalText) noteText = ""
                                refresh()
                            })
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = noteKey.isNotBlank() && noteText.isNotBlank() &&
                            "threadAttachmentWrite" !in loading) {
                        CodexIcon(CodexGlyph.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("保存关联备注")
                    }
                    } }
                    if (currentKey in loading) item { AtlasOperationProgress("正在读取关联资料") }
                    if ("threadAttachmentWrite" in loading) item { AtlasOperationProgress("正在保存资料变更") }
                    errors[currentKey]?.let { error -> item { ErrorText(error) } }
                    errors["threadAttachmentWrite"]?.let { error -> item { ErrorText(error) } }
                    if (all.isNotEmpty()) item { AtlasListHeading("关联资料", all.size) }
                    if (all.isEmpty() && data[currentKey] != null && currentKey !in loading) {
                        item { CodexEmptyState("没有关联资料", "添加备注后将在此显示；图片附件仍在对话输入框管理。") }
                    }
                    items(all, key = {
                        it.optString("id").ifBlank {
                            it.optString("attachmentType") + ":" + it.optString("identityKey")
                        }
                    }) { item ->
                        MaterialPanel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CodexIcon(CodexGlyph.Attachment, null, tint = Ink.copper)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(PresentationPolicy.meaningful(item.optString("identityKey"))
                                        .ifEmpty { "未命名资料" },
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(if (item.optString("attachmentType") == "note") "文本备注" else "关联资料",
                                        color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CodexSecondaryButton(onClick = {
                                    reader = PresentationPolicy.meaningful(
                                        item.optJSONObject("payload")?.optString("text"))
                                        .ifEmpty { "此资料没有可展示的文本内容。" }
                                },
                                    modifier = Modifier.weight(1f)) { Text("查看详情") }
                                CodexSecondaryButton(onClick = { removeTarget = item },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.red)) {
                                    Text("删除资料")
                                }
                            }
                        }
                    }
                    if (cursor.isNotBlank()) item {
                        CodexSecondaryButton(onClick = {
                            val threadId = active ?: return@CodexSecondaryButton
                            val nextPage = page + 1
                            page = nextPage
                            vm.refresh(baseKey + nextPage, "thread/attachment/list",
                                JSONObject().put("threadId", threadId)
                                    .put("cursor", cursor).put("limit", 50))
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = currentKey !in loading) { Text("加载更多关联资料") }
                    }
                }
            }
        }
    }
    removeTarget?.let { item ->
        CodexConfirmDialog(
            title = "删除会话关联资料",
            message = "所选资料会从当前会话的关联记录中删除。任务正文和本地文件保留。",
            detail = "将删除「${PresentationPolicy.meaningful(item.optString("identityKey")).ifEmpty { "当前资料" }}」",
            confirmLabel = "确认删除资料", destructive = true,
            onConfirm = {
                active?.let { id ->
                    vm.rpc("threadAttachmentWrite", "thread/attachment/remove",
                        JSONObject().put("threadId", id)
                            .put("attachmentType", item.optString("attachmentType"))
                            .put("identityKey", item.optString("identityKey")), after = { refresh() })
                }
                removeTarget = null
            }, onDismiss = { removeTarget = null }
        )
    }
    reader?.let { body ->
        FullTextReaderDialog("会话资料详情", body) { reader = null }
    }
}
