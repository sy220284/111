package io.github.sy220284.codexandroid

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
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
    val loading by vm.loading.collectAsState()
    val account by vm.data.collectEntries("account")
    val voiceEpoch by vm.bridge.connectedSession.collectAsState()
    val voiceRevision by vm.catalogWarmup.accountRevision.collectAsState()
    val threadProvider by vm.activeModelProvider.collectAsState()
    val identity = account["account"]?.optJSONObject("account")
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<JSONObject?>(null) }
    var snapshot by remember { mutableLongStateOf(0L) }
    var cursor by remember { mutableStateOf("") }
    var historyLines by remember { mutableStateOf(emptyList<String>()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionError = !it
        if (it) {
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.start()
            else permissionGrantedStart = true
        }
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (permissionGrantedStart) {
                    permissionGrantedStart = false
                    controller.start()
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
    var expanded by remember { mutableStateOf(false) }
    MaterialPanel(Modifier.fillMaxWidth().testTag("realtime-voice-controls")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CodexIcon(CodexGlyph.Microphone, null, tint = if (live) Ink.jade else Ink.copper)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("实时语音", style = MaterialTheme.typography.titleMedium)
                Text(when (controller.state) {
            RealtimeVoiceController.State.Idle -> "语音未开启"
            RealtimeVoiceController.State.Starting -> "正在连接实时语音"
            RealtimeVoiceController.State.Live -> if (controller.muted) "麦克风已静音" else "麦克风正在工作"
            RealtimeVoiceController.State.Stopping -> "正在结束语音"
            RealtimeVoiceController.State.StopFailed -> "语音结束尚未确认"
        }, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
            }
            CodexIconAction(onClick = { expanded = !expanded }) {
                CodexIcon(if (expanded) CodexGlyph.ExpandLess else CodexGlyph.ExpandMore, "语音详情")
            }
            CodexIconAction(onClick = { controller.stop(); onDismiss() }) { CodexIcon(CodexGlyph.Close, "关闭实时语音") }
        }
        if (live) {
            val level = if (controller.muted) 0f else controller.inputLevel
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                repeat(18) { index ->
                    val threshold = (index + 1) / 18f
                    Box(Modifier.weight(1f).height((5 + 20 * threshold).dp)
                        .background(if (level >= threshold) Ink.jade else Ink.border, RoundedCornerShape(3.dp)))
                }
            }
        }
        if (permissionError) CodexNotice("需要麦克风权限。也可以在系统设置中为 Codex 开启。", NoticeTone.Warning)
        if (controller.error.isNotBlank()) CodexNotice(controller.error, NoticeTone.Error)
        CapabilityFeedback(operation)
        if (idle) {
            CodexPrimaryButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) {
                    permissionError = false
                    controller.start()
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = AccountVoiceSettingsLease.key !in loading,
                modifier = Modifier.fillMaxWidth()) { Text("开始实时语音") }
            val epoch = voiceEpoch
            if (threadProvider == "openai" && identity?.optString("type") == "chatgpt" && epoch != null) {
                key(identity.toString(), epoch, voiceRevision) {
                    AccountVoiceSettings(vm, identity.toString(), epoch, voiceRevision)
                }
            } else {
                Text("声音由当前服务端决定；ChatGPT 账号声音可在账号设置中管理。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            }
        } else if (controller.state != RealtimeVoiceController.State.Stopping) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CodexSecondaryButton(onClick = { controller.stop() }) { Text("结束语音") }
                if (live) CodexTextAction(onClick = { controller.muted = !controller.muted }) {
                    Text(if (controller.muted) "取消静音" else "静音")
                }
            }
        }
        if (expanded) Column(Modifier.fillMaxWidth().heightIn(max = atlasSheetHeight() * .36f)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
}
