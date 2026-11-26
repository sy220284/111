package io.github.sy220284.codexandroid

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
internal fun NativeRemoteDeliveryDialog(vm: CodexViewModel, request: RemoteDeliveryRequest, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var data by remember(request) { mutableStateOf<RemoteFileDelivery.Payload?>(null) }
    var loading by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var saved by remember(request) { mutableStateOf(false) }
    var operation by remember(request) { mutableStateOf<Job?>(null) }
    var saving by remember(request) { mutableStateOf(false) }
    var verifying by remember(request) { mutableStateOf(false) }
    var receivedBytes by remember(request) { mutableLongStateOf(0L) }
    var verifiedBytes by remember(request) { mutableLongStateOf(0L) }
    var totalBytes by remember(request) { mutableLongStateOf(0L) }
    val session by vm.bridge.connectedSession.collectAsState()
    val revision by vm.catalogWarmup.accountRevision.collectAsState()
    fun owns() = request.owns(vm)
    val activeThread by vm.activeThread.collectAsState()
    val switching by vm.threadSwitching.collectAsState()
    LaunchedEffect(session, revision, activeThread, switching) {
        if (!owns()) { operation?.cancel(); onDismiss() }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val payload = data
        if (uri != null && payload != null && owns()) {
            loading = true; saving = true; verifying = false; saved = false; error = null
            verifiedBytes = 0L; totalBytes = payload.size
            operation = scope.launch {
                try {
                    RemoteFileDelivery.save(vm, context.contentResolver, uri, payload,
                        onVerified = { count -> verifiedBytes = count }, onWritten = { verifying = true })
                    saved = true
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "文件保存失败" }
                finally { loading = false; saving = false; verifying = false }
            }
        }
    }
    CodexDialogFrame(onDismissRequest = { if (!loading) onDismiss() }, title = { Text("远端文件交付") },
        text = {
            Column(Modifier.fillMaxWidth().testTag("remote-file-delivery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备：" + request.binding.id)
                Text("文件：" + request.target)
                Text("只读取当前绑定的远端工作区文件，单文件上限 512 MiB。重试时重新验证账号、工作区及源文件；具备可信账号身份时支持重启后续传，否则只在当前连接内续传。")
                if (loading) {
                    if (verifying && totalBytes > 0L) {
                        AtlasOperationProgress(label = "正在回读校验 · $verifiedBytes / $totalBytes 字节",
                            progress = (verifiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    } else if (!saving && totalBytes > 0L) {
                        AtlasOperationProgress(label = "正在读取 · $receivedBytes / $totalBytes 字节",
                            progress = (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    } else {
                        AtlasOperationProgress(label = if (verifying) "正在校验空文件…" else if (saving) "正在保存文件…" else "正在读取远端文件…")
                    }
                }
                data?.let { payload ->
                    Text(payload.name + " · " + payload.size + " 字节", style = MaterialTheme.typography.bodyMedium)
                    Text(if (payload.durableResume) "此文件已建立可重启恢复的断点" else "此账号模式仅支持当前连接内续传",
                        color = Ink.muted, style = MaterialTheme.typography.labelSmall)
                    var details by remember(payload) { mutableStateOf(false) }
                    CodexTextAction(onClick = { details = !details }) { Text(if (details) "收起校验信息" else "查看校验信息") }
                    if (details) Text("SHA-256: " + payload.sha256, style = MaterialTheme.typography.labelSmall)
                    if (saved) Text("已保存，文件字节回读校验通过。", color = Ink.jade)
                }
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            CodexTextAction(onClick = {
                if (data != null) create.launch(data!!.name)
                else {
                    loading = true; error = null; totalBytes = 0L; receivedBytes = 0L
                    operation = scope.launch {
                        try { data = RemoteFileDelivery.load(vm, request.thread, request.binding,
                            request.target, request.epoch, request.revision, context.cacheDir,
                            onProgress = { received, total -> receivedBytes = received; totalBytes = total }) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "远端文件读取失败" }
                        finally { loading = false }
                    }
                }
            }, enabled = !loading && owns()) { Text(if (saved) "另存为" else if (data != null) "保存到手机" else if (error != null) "重新读取" else "确认读取") }
        }, dismissButton = {
            CodexTextAction(onClick = {
                if (loading) {
                    // Saving is an explicit system-document write. A cancelled write may leave a partial destination.
                    operation?.cancel()
                    error = if (saving) "已取消保存，所选位置可能有未完成文件，可重新保存。" else "已取消读取，同一文件可继续读取。"
                } else onDismiss()
            }) { Text(if (loading) "取消操作" else "关闭") }
        })
}
