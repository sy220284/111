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
    val errors by vm.errors.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    LaunchedEffect(engineStatus) {
        if (engineStatus == EngineStatus.Connected) vm.refresh("models", "model/list")
    }
    val choices = records(models["models"]).filter { !it.optBoolean("hidden") }
        .map { item ->
            val id = item.optString("id").ifBlank { item.optString("model") }
            CodexChoice(id, item.optString("displayName").ifBlank { id },
                item.optString("description"))
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    CodexChoiceSheet(
        title = "选择运行模型",
        description = when {
            engineStatus != EngineStatus.Connected ->
                "本机内核尚未连接。可查看已保存的模型选择；连接内核后会自动刷新可用目录。"
            "models" in loading -> "正在读取内核可用模型…"
            errors["models"] != null -> "读取模型目录失败：${errors["models"]}。可手动重新加载。"
            else -> "更改只影响后续任务；已运行任务沿用当前模型。"
        },
        choices = if (choices.isEmpty()) listOf(
            CodexChoice(
                if (engineStatus == EngineStatus.Connected) "__reload" else "__connect",
                if (engineStatus == EngineStatus.Connected) "重新加载模型目录" else "连接本机内核",
                if (engineStatus == EngineStatus.Connected) "重新读取可用模型" else "连接后读取模型目录"
            ),
            CodexChoice("", "遵循默认模型", "保留内核的默认模型设置")
        ) else listOf(CodexChoice("", "遵循默认模型", "使用当前服务的默认选择")) + choices,
        selectedId = selected,
        onSelect = { id ->
            when (id) {
                "__reload" -> vm.refresh("models", "model/list")
                "__connect" -> vm.connect()
                else -> { vm.modelId.value = id; onDismiss() }
            }
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
