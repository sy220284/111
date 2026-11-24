package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 编辑器为独立全屏任务：所有关闭路径共享未保存保护，文件操作离开 UI 线程。 */
@Composable
internal fun FileEditor(file: File, root: File, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var original by remember(file.path) { mutableStateOf<String?>(null) }
    var draft by remember(file.path) { mutableStateOf("") }
    var failure by remember(file.path) { mutableStateOf<String?>(null) }
    var saving by remember(file.path) { mutableStateOf(false) }
    var loading by remember(file.path) { mutableStateOf(true) }
    var saved by remember(file.path) { mutableStateOf(false) }
    var confirmExit by remember(file.path) { mutableStateOf(false) }
    var retry by remember(file.path) { mutableIntStateOf(0) }
    val dirty = original != null && draft != original

    LaunchedEffect(file.path, retry) {
        loading = true
        failure = null
        try {
            val text = withContext(Dispatchers.IO) {
                WorkspaceFileActions.readEditableText(root, file)
            }
            original = text
            draft = text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = PresentationPolicy.readableError(error.message ?: "文件读取失败")
        } finally {
            loading = false
        }
    }

    fun requestClose() {
        if (saving) return
        if (dirty) confirmExit = true else onBack()
    }

    Dialog(onDismissRequest = { requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(color = Ink.canvas, modifier = Modifier.fillMaxSize().testTag("workspace-file-editor")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { requestClose() }, enabled = !saving,
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ArrowBack, "返回文件列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            loading -> "正在读取文件"
                            dirty -> "有未保存修改"
                            saved -> "已保存"
                            original != null -> "本地文件编辑"
                            else -> "无法编辑"
                        }, color = if (dirty) Ink.warning else Ink.muted,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    CodexPrimaryButton(onClick = {
                        val expected = original ?: return@CodexPrimaryButton
                        val submitted = draft
                        saving = true
                        saved = false
                        failure = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    WorkspaceFileActions.saveEditableText(root, file, expected, submitted)
                                }
                                original = submitted
                                saved = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = PresentationPolicy.readableError(error.message ?: "保存失败")
                            } finally {
                                saving = false
                            }
                        }
                    }, enabled = dirty && !loading, busy = saving,
                        modifier = Modifier.testTag("workspace-editor-save")) { Text("保存") }
                }
                when {
                    loading -> AtlasOperationProgress("正在读取文件")
                    original == null -> CodexRecoveryState("文件无法读取",
                        failure ?: "请检查文件是否仍存在。", "重新读取", { retry++ })
                    else -> {
                        if (failure != null) {
                            AtlasFieldFeedback(failure!!, isError = true)
                            if (dirty) TextButton(onClick = { clipboard.setText(AnnotatedString(draft)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-editor-copy-draft")) {
                                Text("复制未保存草稿")
                            }
                        }
                        if (saving) AtlasOperationProgress("正在保存文件")
                        OutlinedTextField(draft, { draft = it; saved = false; failure = null },
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("workspace-editor-content"),
                            enabled = !saving,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
    if (confirmExit) CodexConfirmDialog(
        title = "放弃未保存的修改？",
        message = "返回后，尚未保存的编辑内容将丢失。",
        confirmLabel = "放弃修改", destructive = true,
        onConfirm = { confirmExit = false; onBack() },
        onDismiss = { confirmExit = false }
    )
}
