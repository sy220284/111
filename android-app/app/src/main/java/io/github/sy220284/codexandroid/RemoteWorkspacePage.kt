package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Direct downloads use the existing verified transport; remote commands require explicit submission. */
@Composable
internal fun RemoteWorkspacePage(vm: CodexViewModel, thread: String?, binding: RemoteExecutionBinding,
    files: Boolean, navigate: (String) -> Unit) {
    val session = vm.bridge.connectedSession.collectAsStateWithLifecycle().value
    val revision = vm.catalogWarmup.accountRevision.collectAsStateWithLifecycle().value
    val switching by vm.threadSwitching.collectAsStateWithLifecycle()
    val transfer by AppDataTransfer.state.collectAsStateWithLifecycle()
    var path by remember(thread, binding, session, revision) { mutableStateOf("") }
    var command by remember(thread, binding, session, revision) { mutableStateOf("") }
    var error by remember(thread, binding, session, revision) { mutableStateOf<String?>(null) }
    var delivery by remember(thread, binding, session, revision) { mutableStateOf<RemoteDeliveryRequest?>(null) }
    val ready = session != null && !switching && !transfer.busy && !transfer.pendingRestore
    var directories by remember(thread, binding, session, revision) { mutableStateOf(listOf("")) }
    var directoryOpen by remember(thread, binding, session, revision) { mutableStateOf(false) }
    var directoryRefresh by remember(thread, binding, session, revision) { mutableIntStateOf(0) }
    var listing by remember(thread, binding, session, revision) { mutableStateOf<List<RemoteDirectoryEntry>?>(null) }
    var listingError by remember(thread, binding, session, revision) { mutableStateOf<String?>(null) }
    var listingBusy by remember(thread, binding, session, revision) { mutableStateOf(false) }
    val folder = directories.last()
    LaunchedEffect(directoryOpen, folder, directoryRefresh, ready, thread, binding, session, revision) {
        listing = null
        listingError = null
        listingBusy = false
        if (!directoryOpen || !ready || thread == null || session == null) return@LaunchedEffect
        listingBusy = true
        try {
            listing = RemoteDirectory.read(vm, RemoteDeliveryRequest(thread, binding, folder, session, revision))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { listingError = failure.message ?: "无法读取远端目录" }
        finally { listingBusy = false }
    }
    fun current(): Boolean = vm.activeThread.value == thread &&
        vm.conversationState.value.remote == binding && vm.bridge.connectedSession.value == session &&
        vm.catalogWarmup.accountRevision.value == revision && !vm.threadSwitching.value &&
        vm.globalWorkSnapshot().canStartNewTask
    fun prepare(instruction: String) {
        if (!current()) { error = "任务执行设备或会话已变化，请重新打开操作"; return }
        if (appendTaskInstruction(vm, thread, instruction)) navigate("chat")
    }
    ScreenColumn {
        SectionIntro(if (files) "远端工作区" else "远端命令", "当前任务执行于 " + binding.id)
        MaterialPanel(Modifier.fillMaxWidth()) {
            StatusPill(if (ready) "本机内核已连接" else "等待连接或任务切换",
                tone = if (ready) StatusTone.Success else StatusTone.Warning)
            Text(binding.id, style = MaterialTheme.typography.titleMedium)
            Text(binding.cwd, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            Text("浏览目录和读取文件时将再次核对远端设备和工作目录。", color = Ink.muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (files) {
            CodexPrimaryButton(onClick = { listing = null; directoryOpen = true; directoryRefresh++ },
                enabled = ready && thread != null, modifier = Modifier.fillMaxWidth().testTag("remote-directory-open")) {
                Text("浏览远端目录")
            }
            if (directoryOpen) {
                CodexDetailSheet(title = "远端文件", subtitle = if (folder.isEmpty()) binding.cwd else folder,
                    onDismiss = { directoryOpen = false }, startExpanded = true) {
                    CodexAdaptiveActions(
                        first = { modifier -> CodexSecondaryButton(onClick = { listing = null; directories = directories.dropLast(1) },
                            enabled = directories.size > 1 && !listingBusy, modifier = modifier) { Text("上一级") } },
                        second = { modifier -> CodexSecondaryButton(onClick = { listing = null; directoryRefresh++ },
                            enabled = ready && !listingBusy, modifier = modifier) { Text("刷新目录") } }
                    )
                    if (!ready) CodexNotice("等待连接或任务切换", NoticeTone.Warning)
                    if (listingBusy) AtlasOperationProgress("正在读取远端目录")
                    listingError?.let { ErrorText(it) }
                    listing?.let { entries ->
                        Text("共 ${entries.size} 项", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                        if (entries.isEmpty()) CodexNotice("当前目录为空", NoticeTone.Info)
                        var shown by remember(folder, entries) { mutableIntStateOf(80) }
                        entries.take(shown).forEach { entry ->
                            CodexTextAction(onClick = {
                                val relative = entry.relative
                                if (!current() || relative == null) return@CodexTextAction
                                if (entry.kind == "directory") { listing = null; directories = directories + relative }
                                else { path = relative; directoryOpen = false }
                            }, enabled = ready && !listingBusy && entry.relative != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CodexIcon(if (entry.kind == "directory") CodexGlyph.Folder else CodexGlyph.Project, null)
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(when {
                                            entry.relative == null -> "链接或不支持的文件名"
                                            entry.kind == "directory" -> "打开目录"
                                            else -> "选择文件"
                                        }, style = MaterialTheme.typography.labelSmall, color = Ink.muted)
                                    }
                                    if (entry.relative != null) CodexIcon(CodexGlyph.ChevronRight, null)
                                }
                            }
                        }
                        if (shown < entries.size) CodexSecondaryButton(onClick = { shown += 80 },
                            modifier = Modifier.fillMaxWidth()) { Text("显示更多文件") }
                    }
                }
            }
            Text("文件交付", style = MaterialTheme.typography.titleMedium)
            Text("输入远端工作区内的文件路径，核对并保存到手机。", color = Ink.muted)
            ActionQuestionRow(path, { path = it; error = null }, "远端文件路径",
                placeholder = "例如 README.md", directText = true,
                enabled = ready && thread != null, replyTag = "remote-workspace-path")
            if (thread == null) CodexNotice("先发送指令建立远端会话，再读取文件。", NoticeTone.Info)
            CodexPrimaryButton(onClick = {
                try {
                    check(current()) { "任务执行设备或会话已变化，请重新打开操作" }
                    val targetThread = requireNotNull(thread) { "请先建立远端会话" }
                    val epoch = requireNotNull(session) { "请先连接运行核心" }
                    val target = path
                    RemoteFileDelivery.selection(binding, target)
                    val request = RemoteDeliveryRequest(targetThread, binding, target, epoch, revision)
                    check(request.owns(vm)) { "核心未确认当前远端会话" }
                    error = null
                    delivery = request
                } catch (failure: Exception) {
                    error = failure.message ?: "无法读取远端文件，请核对路径"
                }
            }, enabled = ready && thread != null && path.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("remote-workspace-download")) { Text("核对并下载文件") }
            CodexSecondaryButton(onClick = { prepare("请查看当前远端工作目录的文件，并列出目录内容。") },
                enabled = ready, modifier = Modifier.fillMaxWidth().testTag("remote-workspace-list")) { Text("让 Codex 查看目录") }
            Text("编辑和上传仍通过当前远端任务处理。", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("准备远端命令", style = MaterialTheme.typography.titleMedium)
            ActionQuestionRow(command, { command = it; error = null }, "命令内容",
                placeholder = "例如 git status", directText = true, singleLine = false,
                enabled = ready, replyTag = "remote-workspace-command")
            CodexPrimaryButton(onClick = {
                prepare("请在当前远端工作区核对并执行以下命令，说明风险、执行结果及错误：\n" + command.trim())
            }, enabled = ready && command.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("remote-command-prepare")) { Text("检查并加入对话草稿") }
            Text("在对话中确认发送后，由远端智能体执行；此处不会启动手机终端。", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { ErrorText(it) }
        CodexSecondaryButton(onClick = {
            if (!current()) error = "任务执行设备或会话已变化，请重新打开操作"
            else if (vm.resetConversation(vm.workingDirectory, null)) navigate("chat")
            else error = vm.errors.value["conversation"] ?: "当前无法新建本机任务"
        }, enabled = !switching && !transfer.busy && !transfer.pendingRestore,
            modifier = Modifier.fillMaxWidth()) { Text("新建手机本机任务") }
    }
    delivery?.let { request -> NativeRemoteDeliveryDialog(vm, request) { delivery = null } }
}
