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
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context.applicationContext) { WorkspaceEditorDraftStore(File(context.filesDir, "editor-drafts")) }
    var recoveredConflict by remember(file.path) { mutableStateOf(false) }
    var recovered by remember(file.path) { mutableStateOf(false) }
    var discard by remember(file.path) { mutableStateOf(false) }
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
            val (text, retained) = withContext(Dispatchers.IO) {
                WorkspaceFileActions.readEditableText(root, file) to store.read(root, file)
            }
            original = text
            draft = retained?.edited ?: text
            recovered = retained != null
            recoveredConflict = retained != null && retained.base != text && retained.edited != text
            if (recoveredConflict) failure = "文件已在其他位置更改；已保留原草稿。请复制草稿或核对修改后再保存。"
            if (retained != null && retained.edited == text) AppDataTransfer.mutateLocal { store.clear(root, file) }
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
                    CodexIconAction(onClick = { requestClose() }, enabled = !saving,
                        modifier = Modifier.size(48.dp)) {
                        CodexIcon(CodexGlyph.ArrowBack, "返回文件列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            loading -> "正在读取文件"
                            recoveredConflict -> "草稿与磁盘版本冲突"
                            recovered && dirty -> "已恢复编辑草稿"
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
                                AppDataTransfer.mutateLocal {
                                    WorkspaceFileActions.saveEditableText(root, file, expected, submitted)
                                    store.clear(root, file)
                                }
                                original = submitted
                                recovered = false
                                recoveredConflict = false
                                saved = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = PresentationPolicy.readableError(error.message ?: "保存失败")
                            } finally {
                                saving = false
                            }
                        }
                    }, enabled = dirty && !loading && !recoveredConflict, busy = saving,
                        modifier = Modifier.testTag("workspace-editor-save")) {
                        CodexIcon(CodexGlyph.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("保存")
                    }
                }
                when {
                    loading -> AtlasOperationProgress("正在读取文件")
                    original == null -> CodexRecoveryState("文件无法读取",
                        failure ?: "请检查文件是否仍存在。", "重新读取", { retry++ })
                    else -> {
                        if (failure != null) {
                            AtlasFieldFeedback(failure!!, isError = true)
                            if (dirty) CodexTextAction(onClick = { clipboard.setText(AnnotatedString(draft)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-editor-copy-draft")) {
                                Text("复制未保存草稿")
                            }
                        }
                        if (recoveredConflict) CodexTextAction(onClick = { recoveredConflict = false; failure = null },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("已核对，允许保存到当前文件") }
                        if (dirty && !saving) CodexTextAction(onClick = { discard = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("workspace-editor-discard")) { Text("放弃编辑草稿") }
                        if (saving) AtlasOperationProgress("正在保存文件")
                        CodexInputField(draft, { draft = it; saved = false; failure = null },
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("workspace-editor-content"),
                            enabled = !saving,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    }
                }
            }
        }
    }
    if (confirmExit) CodexConfirmDialog(
        title = "保留草稿并返回？",
        message = "原文件保持不变。下次打开此文件可继续编辑。",
        confirmLabel = "保留草稿",
        onConfirm = {
            confirmExit = false
            val base = original ?: return@CodexConfirmDialog
            val edited = draft
            saving = true
            scope.launch {
                try {
                    AppDataTransfer.mutateLocal {
                        val prior = store.read(root, file)
                        store.save(root, file, WorkspaceEditorDraft(if (recoveredConflict) prior?.base ?: base else base, edited))
                    }
                    onBack()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = PresentationPolicy.readableError(error.message ?: "草稿保存失败") }
                finally { saving = false }
            }
        },
        onDismiss = { confirmExit = false }
    )
    if (discard) CodexConfirmDialog(
        title = "放弃未保存的修改？",
        message = "清除编辑草稿，原文件保持不变。",
        confirmLabel = "放弃修改", destructive = true,
        onConfirm = {
            discard = false
            saving = true
            scope.launch {
                try {
                    AppDataTransfer.mutateLocal { store.clear(root, file) }
                    onBack()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = PresentationPolicy.readableError(error.message ?: "草稿清除失败") }
                finally { saving = false }
            }
        }, onDismiss = { discard = false }
    )
}
