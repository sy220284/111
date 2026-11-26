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
    val session by vm.bridge.connectedSession.collectAsState()
    val revision by vm.catalogWarmup.accountRevision.collectAsState()
    fun owns() = vm.activeThread.value == request.thread && session == request.epoch &&
        revision == request.revision && vm.remoteBindings.forThread(request.thread) == request.binding
    LaunchedEffect(session, revision) { if (!owns()) onDismiss() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val payload = data
        if (uri != null && payload != null && owns()) {
            loading = true; error = null
            scope.launch {
                try { RemoteFileDelivery.save(context.contentResolver, uri, payload); saved = true }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message ?: "文件保存失败" }
                finally { loading = false }
            }
        }
    }
    CodexDialogFrame(onDismissRequest = onDismiss, title = { Text("远端文件交付") },
        text = {
            Column(Modifier.fillMaxWidth().testTag("remote-file-delivery"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备：" + request.binding.id)
                Text("文件：" + request.target)
                Text("只读取当前绑定的远端工作区文件，单文件上限 8 MiB。")
                if (loading) Text("正在读取或保存文件…")
                data?.let { payload ->
                    Text(payload.name + " · " + payload.bytes.size + " 字节", style = MaterialTheme.typography.bodyMedium)
                    Text("SHA-256: " + payload.sha256, style = MaterialTheme.typography.labelSmall)
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
                    scope.launch {
                        try { data = RemoteFileDelivery.load(vm, request.thread, request.binding,
                            request.target, request.epoch, request.revision) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { error = failure.message ?: "远端文件读取失败" }
                        finally { loading = false }
                    }
                }
            }, enabled = !loading && owns()) { Text(if (data == null) "确认读取" else "保存到手机") }
        }, dismissButton = { CodexTextAction(onClick = onDismiss) { Text("关闭") } })
}
