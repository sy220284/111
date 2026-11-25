package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import org.json.JSONArray
import org.json.JSONObject

/** Read actual CLI versions using the existing local command RPC; no download or install. */
@Composable
internal fun DeveloperToolStatus(vm: CodexViewModel) {
    val data by vm.data.collectAsState()
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val engine by vm.bridge.status.collectAsState()
    val key = "developerToolStatus"
    val script = """
        for name in git rg curl python3 pip node npm; do
          if command -v "${'$'}name" >/dev/null 2>&1; then
            output=${'$'}("${'$'}name" --version 2>&1); code=$?
            printf '%s\t%s\t%s\n' "${'$'}name" "${'$'}code" "${'$'}output"
          else
            printf '%s\t127\t命令未找到\n' "${'$'}name"
          fi
        done
    """.trimIndent()
    MaterialPanel(Modifier.fillMaxWidth()) {
        Text("本机开发工具", style = MaterialTheme.typography.titleMedium)
        Text("随签名应用更新，检查实际运行版本；联网能力另行验证。", color = Ink.muted,
            style = MaterialTheme.typography.bodyMedium)
        CodexSecondaryButton(onClick = {
            vm.refresh(key, "command/exec", JSONObject()
                .put("command", JSONArray().put("/system/bin/sh").put("-c").put(script))
                .put("cwd", vm.activeWorkspace.value)
                .put("timeoutMs", 30000)
                .put("sandboxPolicy", JSONObject().put("type", "externalSandbox").put("networkAccess", "restricted")))
        }, enabled = engine == EngineStatus.Connected && key !in loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("developer-tool-check")) {
            Text(if (key in loading) "检查中" else "检查工具")
        }
        errors[key]?.let { ErrorText(it) }
        data[key]?.let { result ->
            if (result.optInt("exitCode", -1) != 0) ErrorText("工具检查未完整执行")
            else result.optString("stdout").lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val columns = line.split('\t', limit = 3)
                if (columns.size == 3) Text(columns[0] + "：" +
                    if (columns[1] == "0") columns[2] else "运行失败（" + columns[1] + "）")
            }
        }
    }
}
