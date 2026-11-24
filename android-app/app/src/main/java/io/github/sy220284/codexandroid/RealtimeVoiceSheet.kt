package io.github.sy220284.codexandroid

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

@Composable
internal fun RealtimeVoiceSheet(
    vm: CodexViewModel, threadId: String, controller: RealtimeVoiceController, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permissionError by remember { mutableStateOf(false) }
    var permissionGrantedStart by remember { mutableStateOf(false) }
    val operation = rememberCapabilityOperation(vm)
    var voices by remember { mutableStateOf(emptyList<String>()) }
    var voice by remember { mutableStateOf("") }
    var showVoices by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var snapshot by remember { mutableLongStateOf(0L) }
    var cursor by remember { mutableStateOf("") }
    var historyLines by remember { mutableStateOf(emptyList<String>()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionError = !it
        if (it) {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.start(voice)
            else permissionGrantedStart = true
        }
    }
    val currentVoice by rememberUpdatedState(voice)
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (permissionGrantedStart) {
                    permissionGrantedStart = false
                    controller.start(currentVoice)
                }
                Lifecycle.Event.ON_STOP -> {
                    permissionGrantedStart = false
                    controller.stop()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    fun loadVoices() {
        operation.launch {
            val options = vm.capabilityRequest("thread/realtime/listVoices").getJSONObject("voices")
            // The selected provider validates the voice against its configured protocol version.
            val lists = listOf("v1", "v2", "v3").mapNotNull(options::optJSONArray).filter { it.length() > 0 }
            voices = lists.flatMap { items ->
                (0 until items.length()).map(items::getString)
            }.distinct()
            showVoices = true
        }
    }
    fun loadHistory(next: Boolean) {
        operation.launch {
            val params = JSONObject().put("threadId", threadId).put("limit", 30)
            if (next) params.put("cursor", cursor).put("snapshotEnd", snapshot)
            val result = vm.capabilityRequest("thread/realtime/list", params)
            val lines = result.objects().mapNotNull { item ->
                val content = item.optJSONObject("item") ?: return@mapNotNull null
                val transcript = content.optJSONObject("channels")?.optJSONObject("voice_transcript") ?: content
                val value = transcript.value("text")
                if (value.isBlank()) null else
                    (if (transcript.value("role") == "user") "你  " else "Codex  ") + value
            }
            history = result
            historyLines = if (next) historyLines + lines else lines
            cursor = result.value("nextCursor")
            snapshot = result.optLong("snapshotEnd")
        }
    }
    val live = controller.state == RealtimeVoiceController.State.Live
    val idle = controller.state == RealtimeVoiceController.State.Idle
    CodexDetailSheet("实时语音", onDismiss = onDismiss,
        subtitle = "使用麦克风与当前会话实时交流。离开面板或切到后台会结束语音。") {
        Text(when (controller.state) {
            RealtimeVoiceController.State.Idle -> "语音未开启"
            RealtimeVoiceController.State.Starting -> "正在连接实时语音"
            RealtimeVoiceController.State.Live -> if (controller.muted) "麦克风已静音" else "麦克风正在工作"
            RealtimeVoiceController.State.Stopping -> "正在结束语音"
        }, style = MaterialTheme.typography.titleMedium)
        if (permissionError) CodexNotice("需要麦克风权限。也可以在系统设置中为 Codex 开启。", NoticeTone.Warning)
        if (controller.error.isNotBlank()) CodexNotice(controller.error, NoticeTone.Error)
        CapabilityFeedback(operation)
        if (idle) {
            CodexPrimaryButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) {
                    permissionError = false
                    controller.start(voice)
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.fillMaxWidth()) { Text("开始实时语音") }
            Box {
                CodexTextAction(onClick = { if (voices.isEmpty()) loadVoices() else showVoices = true },
                    enabled = !operation.busy) { Text(voice.ifBlank { "选择声音 · 服务器默认" }) }
                CodexPopupMenu(showVoices, { showVoices = false }) {
                    DropdownMenuItem(text = { Text("服务器默认") }, onClick = { voice = ""; showVoices = false })
                    voices.forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = { voice = option; showVoices = false })
                    }
                }
            }
        } else if (controller.state != RealtimeVoiceController.State.Stopping) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CodexSecondaryButton(onClick = { controller.stop() }) { Text("结束语音") }
                if (live) CodexTextAction(onClick = { controller.muted = !controller.muted }) {
                    Text(if (controller.muted) "取消静音" else "静音")
                }
            }
        }
        if (live) {
            CodexInputField(input, { input = it.take(4_000) }, label = { Text("也可以输入文字") },
                modifier = Modifier.fillMaxWidth())
            fun submit(speech: Boolean) {
                val draft = input
                controller.sendText(draft.trim(), speech) { if (input == draft) input = "" }
            }
            Row {
                CodexSecondaryButton(onClick = { submit(false) },
                    enabled = input.isNotBlank() && !controller.sending) { Text("发送文字") }
                CodexTextAction(onClick = { submit(true) },
                    enabled = input.isNotBlank() && !controller.sending) { Text("朗读文字") }
            }
        }
        if (controller.userTranscript.isNotBlank()) Text("你  ${controller.userTranscript}")
        if (controller.assistantTranscript.isNotBlank()) Text("Codex  ${controller.assistantTranscript}")
        CodexTextAction(onClick = { loadHistory(false) }, enabled = !operation.busy) { Text("读取语音历史") }
        history?.let {
            Text(when (it.value("coverage")) {
                "pending" -> "语音历史正在整理，请稍后刷新"
                "unsupported" -> "当前会话的存储方式不支持读取语音历史"
                else -> if (historyLines.isEmpty()) "当前没有保存的语音文字" else "语音历史"
            }, color = Ink.muted)
            historyLines.forEach { line -> Text(line) }
            if (cursor.isNotBlank()) CodexTextAction(onClick = { loadHistory(true) },
                enabled = !operation.busy) { Text("读取更多") }
        }
    }
}
