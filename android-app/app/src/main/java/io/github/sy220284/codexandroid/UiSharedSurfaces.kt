package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * V5 公共呈现：选择=Sheet、长工作=Page、危险操作=ConfirmDialog、
 * 状态=Sheet、附件=独立 Sheet；调用和结果复用已有真实 ViewModel。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineStatusSheet(
    vm: CodexViewModel, onDismiss: () -> Unit, navigate: (String) -> Unit
) {
    val status by vm.bridge.status.collectAsState()
    val terminal by vm.terminalSession.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.canvas, contentColor = Ink.parchment) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text("运行状态", style = MaterialTheme.typography.headlineSmall)
            val label = when (status) {
                EngineStatus.Connected -> "本机内核已连接"
                EngineStatus.Starting -> "内核正在启动"
                is EngineStatus.Unavailable -> "内核连接异常"
            }
            val tone = if (status == EngineStatus.Connected) StatusTone.Success else StatusTone.Warning
            StatusPill(label, tone = tone)
            if (status is EngineStatus.Unavailable) {
                Text((status as EngineStatus.Unavailable).reason,
                    color = Ink.red, style = MaterialTheme.typography.bodyMedium)
            }
            Text("状态只显示实际进程和会话信息；连接、诊断、终端分别执行独立动作。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            if (status != EngineStatus.Connected) {
                OutlinedButton(onClick = { onDismiss(); vm.connect() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("重新连接本机内核")
                }
            }
            if (terminal.running) {
                OutlinedButton(onClick = { onDismiss(); navigate("terminal") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Terminal, null)
                    Spacer(Modifier.width(8.dp))
                    Text("查看正在运行的终端")
                }
            }
            OutlinedButton(onClick = { onDismiss(); navigate("diagnostics") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Troubleshoot, null)
                Spacer(Modifier.width(8.dp))
                Text("查看诊断和日志")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ModelChoiceSurface(vm: CodexViewModel, onDismiss: () -> Unit) {
    val models by vm.data.collectAsState()
    val selected by vm.modelId.collectAsState()
    val loading by vm.loading.collectAsState()
    LaunchedEffect(Unit) { vm.refresh("models", "model/list") }
    val choices = records(models["models"]).filter { !it.optBoolean("hidden") }
        .map { item ->
            val id = item.optString("id").ifBlank { item.optString("model") }
            CodexChoice(id, item.optString("displayName").ifBlank { id },
                item.optString("description"))
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    CodexChoiceSheet(
        title = "选择运行模型",
        description = if ("models" in loading) "正在读取内核可用模型…"
        else "更改只影响后续任务；已运行任务沿用当前模型。",
        choices = if (choices.isEmpty()) listOf(CodexChoice("__reload", "重新加载模型目录", "从本机内核读取")) else
            listOf(CodexChoice("", "遵循默认模型", "使用当前服务的默认选择")) + choices,
        selectedId = selected,
        onSelect = { id ->
            if (id == "__reload") vm.refresh("models", "model/list")
            else { vm.modelId.value = id; onDismiss() }
        },
        onDismiss = onDismiss
    )
}

@Composable
fun ModeChoiceSurface(vm: CodexViewModel, onDismiss: () -> Unit) {
    val selected by vm.selectedMode.collectAsState()
    val data by vm.data.collectAsState()
    val busy by vm.running.collectAsState()
    LaunchedEffect(Unit) { vm.refresh("modes", "collaborationMode/list") }
    val supported = records(data["modes"])
        .any { it.optString("mode") == "plan" }
    val choices = buildList {
        add(CodexChoice("default", "直接执行", "按要求执行，并报告真实进度"))
        if (supported || selected == "plan") add(
            CodexChoice("plan", "先制定计划", "先展示方案，再由你决定推进步骤")
        )
        if (!supported) add(CodexChoice("__reload", "检查计划模式支持情况", "从运行核心刷新可用模式"))
    }
    CodexChoiceSheet(
        title = "选择协作方式",
        description = if (busy) "当前任务执行中，模式将在下一轮调整。" else
            "仅提供内核支持的执行方式。",
        choices = choices, selectedId = selected,
        onSelect = { id ->
            when {
                id == "__reload" -> vm.refresh("modes", "collaborationMode/list")
                !busy -> { vm.selectedMode.value = id; onDismiss() }
            }
        }, onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentActionsSheet(
    imageCount: Int, importing: Boolean,
    onDismiss: () -> Unit, onPickImage: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("添加附件", style = MaterialTheme.typography.headlineSmall)
            Text("目前支持从本机相册和文件选择图片，最多三张。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            val enabled = !importing && imageCount < LocalImageAttachments.MAX_FILES
            OutlinedButton(onClick = { onDismiss(); onPickImage() },
                enabled = enabled, modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 54.dp).testTag("attachment-pick-image")) {
                Icon(Icons.Outlined.AddPhotoAlternate, null)
                Spacer(Modifier.width(10.dp))
                Text("从相册或文件选择图片")
            }
            if (!enabled) CodexNotice(
                if (importing) "正在导入图片，请完成后再选择。"
                else "最多添加三张图片。移除已有附件后可继续添加。", NoticeTone.Info)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
fun RequiresThreadPanel(
    title: String, onOpenThreads: () -> Unit
) {
    CodexEmptyState(
        title = "先选择任务会话",
        description = "“$title”需要关联具体会话。选择历史会话后即可继续操作。",
        actionLabel = "选择会话", onAction = onOpenThreads
    )
}

/** 原有页面可继续通过旧路由打开；新入口统一弹出此详情。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutInfoSheet(vm: CodexViewModel, onDismiss: () -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
            .navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("关于应用", style = MaterialTheme.typography.headlineSmall)
            Text("版本 v$version · Android 29+", style = MaterialTheme.typography.titleMedium)
            StatusPill(if (status == EngineStatus.Connected) "本机内核已连接" else "内核需要检查",
                good = status == EngineStatus.Connected)
            DividerLine()
            Text("项目来源：sy220284/111", color = Ink.muted)
            Text("运行核心：Codex app-server（本地进程）", color = Ink.muted)
            Text("工程文件和配置保存在 Android 应用私有目录；登录信息由运行核心管理。",
                color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            Text("上游 Codex：Apache-2.0；增量更新组件：HDiffPatch（MIT）。",
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(18.dp))
        }
    }
}
