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

internal data class RemoteDeliveryRequest(val thread: String, val binding: RemoteExecutionBinding,
    val target: String, val epoch: Long, val revision: Long)

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
    var verifiedBytes by remember(request) { mutableLongStateOf(0L) }
    var totalBytes by remember(request) { mutableLongStateOf(0L) }
    val session by vm.bridge.connectedSession.collectAsState()
    val revision by vm.catalogWarmup.accountRevision.collectAsState()
    fun owns() = vm.activeThread.value == request.thread && vm.bridge.connectedSession.value == request.epoch &&
        vm.catalogWarmup.accountRevision.value == request.revision && !vm.threadSwitching.value &&
        vm.remoteBindings.forThread(request.thread) == request.binding
    LaunchedEffect(session, revision) { if (!owns()) onDismiss() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val payload = data
        if (uri != null && payload != null && owns()) {
            loading = true; saving = true; verifying = false; saved = false; error = null
            verifiedBytes = 0L; totalBytes = payload.bytes.size.toLong()
            operation = scope.launch {
                try {
                    RemoteFileDelivery.save(context.contentResolver, uri, payload,
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
                Text("只读取当前绑定的远端工作区文件，单文件上限 8 MiB。")
                if (loading) {
                    if (verifying && totalBytes > 0L) {
                        LinearProgressIndicator(progress = { (verifiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth())
                        Text("正在回读校验 · " + verifiedBytes + " / " + totalBytes + " 字节",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(if (verifying) "正在校验空文件…" else if (saving) "正在保存文件…" else "正在读取远端文件…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                data?.let { payload ->
                    Text(payload.name + " · " + payload.bytes.size + " 字节", style = MaterialTheme.typography.bodyMedium)
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
                    loading = true; error = null
                    operation = scope.launch {
                        try { data = RemoteFileDelivery.load(vm, request.thread, request.binding,
                            request.target, request.epoch, request.revision) }
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
                    error = if (saving) "已取消保存，所选位置可能有未完成文件，可重新保存。" else "已取消读取，可重新读取。"
                } else onDismiss()
            }) { Text(if (loading) "取消操作" else "关闭") }
        })
}
