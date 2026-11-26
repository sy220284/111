package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

/** Read actual CLI versions using the existing local command RPC; no download or install. */
@Composable
internal fun DeveloperToolStatus(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val engine by vm.bridge.status.collectAsState()
    val key = "developerToolStatus"
    val session by vm.bridge.connectedSession.collectAsState()
    var checkedSession by remember { mutableStateOf<Long?>(null) }
    var details by remember { mutableStateOf(false) }
    val capabilities = mapOf("git" to "代码版本管理", "rg" to "快速查找项目内容", "curl" to "访问网络资源",
        "python3" to "运行 Python 脚本", "node" to "运行 JavaScript", "npm" to "管理前端依赖",
        "pip (python3 -m pip)" to "管理 Python 依赖", "Git HTTPS" to "远程 Git 传输")
    MaterialPanel(Modifier.fillMaxWidth()) {
        Text("本机开发工具", style = MaterialTheme.typography.titleMedium)
        Text("智能体按任务选择这些工具。检查仅验证本机命令启动和版本，不联网或安装依赖。", color = Ink.muted,
            style = MaterialTheme.typography.bodyMedium)
        CodexSecondaryButton(onClick = {
            checkedSession = session
            vm.refresh(key, "command/exec", TerminalSessionContract.developerProbe(vm.workingDirectory))
        }, enabled = engine == EngineStatus.Connected && key !in loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("developer-tool-check")) {
            Text(if (key in loading) "检查中" else "检查开发能力")
        }
        errors[key]?.let { ErrorText(it) }
        if (data[key] != null && checkedSession == session) CodexTextAction(onClick = { details = !details }) { Text(if (details) "收起版本详情" else "查看版本详情") }
        data[key]?.takeIf { session != null && checkedSession == session && key !in loading && errors[key] == null }?.let { result ->
            if (result.optInt("exitCode", -1) != 0) ErrorText("工具检查未完整执行")
            else result.optString("stdout").lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val columns = line.split('\t', limit = 3)
                if (columns.size == 3) {
                    val label = capabilities[columns[0]] ?: columns[0]
                    Text(label + " · " + if (columns[1] == "0") (if (columns[0] == "Git HTTPS") "传输组件已注册" else "版本检查通过") else "需要处理",
                        style = MaterialTheme.typography.bodyMedium, color = if (columns[1] == "0") Ink.parchment else Ink.warning)
                    if (details) Text(columns[0] + "：" + columns[2], color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
