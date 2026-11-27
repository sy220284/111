package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

/** Only a base user config layer is editable; version guards the complete event array. */
internal data class HookPolicyRow(val event: String, val group: Int, val index: Int, val handler: JSONObject,
    val groups: JSONArray, val version: String?, val managed: Boolean) {
    val policy: String get() = (handler.opt("onFailure") as? String) ?: "continue"
    val canBlock: Boolean get() = event !in setOf("SessionEnd", "PreModelRequest", "PostModelRequest") &&
        !handler.optBoolean("async")
    fun change(next: String): JSONArray {
        require(!managed && !version.isNullOrBlank())
        require(next == "continue" || next == "block" && canBlock)
        return JSONArray(groups.toString()).apply {
            getJSONObject(group).getJSONArray("hooks").getJSONObject(index).put("onFailure", next)
        }
    }
}

internal object HookPolicyCatalog {
    private val events = listOf("PreModelRequest", "PostModelRequest", "PreToolUse", "PermissionRequest", "PostToolUse",
        "PreCompact", "PostCompact", "SessionStart", "SessionEnd", "UserPromptSubmit", "SubagentStart",
        "SubagentStop", "Stop", "Interrupt")
    fun rows(hooks: JSONObject?, version: String?, managed: Boolean): List<HookPolicyRow> = buildList {
        for (event in events) {
            val groups = hooks?.optJSONArray(event) ?: continue
            for (g in 0 until groups.length()) {
                val handlers = groups.optJSONObject(g)?.optJSONArray("hooks") ?: continue
                for (i in 0 until handlers.length()) {
                    val handler = handlers.optJSONObject(i) ?: continue
                    if (handler.optString("type") in setOf("command", "mcp_tool", "mcpTool"))
                        add(HookPolicyRow(event, g, i, handler, groups, version, managed))
                }
            }
        }
    }
    fun user(read: JSONObject?): List<HookPolicyRow> {
        val layers = read?.optJSONArray("layers") ?: return emptyList()
        val layer = (0 until layers.length()).mapNotNull(layers::optJSONObject).singleOrNull {
            val name = it.optJSONObject("name")
            name != null && name.optString("type") == "user" && name.isNull("profile") && it.isNull("disabledReason")
        } ?: return emptyList()
        return rows(layer.optJSONObject("config")?.optJSONObject("hooks"),
            (layer.opt("version") as? String)?.takeIf { it.isNotBlank() }, false)
    }
}

@Composable
internal fun HookPolicySettings(vm: CodexViewModel, config: JSONObject?, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Pair<HookPolicyRow, String>?>(null) }
    val data by vm.data.collectEntries("hookRequirements")
    val loading by vm.loading.collectAsStateWithLifecycle()
    val errors by vm.errors.collectEntries("hookRequirements", ENGINE_SETTING_SAVE)
    val requirements = data["hookRequirements"]?.optJSONObject("requirements")
    val local = remember(config) { HookPolicyCatalog.user(config) }
    val managed = remember(requirements) { HookPolicyCatalog.rows(requirements?.optJSONObject("hooks"), null, true) }
    val ready = data["hookRequirements"] != null && "hookRequirements" !in loading && "hookRequirements" !in errors
    CodexDetailTrigger("执行检查失败策略", {
        open = true
        vm.refresh("hookRequirements", "configRequirements/read", null)
        vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
    }, Modifier.fillMaxWidth().testTag("hook-policy-settings"))
    if (open) CodexDetailSheet("执行检查失败策略", { open = false }, startExpanded = true) {
        Text("管理用户配置中已有的同步或异步检查。修改可能需要重新信任规则；企业强制规则仅供查看。其他项目和插件检查由其来源管理。", color = Ink.muted)
        ProtocolFeedback(vm, listOf("hookRequirements", ENGINE_SETTING_SAVE), onRetry = {
            vm.refresh("hookRequirements", "configRequirements/read", null)
            vm.refresh("config", "config/read", JSONObject().put("includeLayers", true))
        })
        if (local.isEmpty() && managed.isEmpty() && ready && config != null && "config" !in loading)
            Text("当前用户配置与企业要求中没有可显示的检查策略。", color = Ink.muted)
        for (row in local + managed) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text(PresentationPolicy.hookEvent(row.event.replaceFirstChar { it.lowercase() }) +
                    " · 检查 ${row.group + 1}.${row.index + 1}", style = MaterialTheme.typography.titleSmall)
                Text(if (row.managed) "企业规则 · 只读" else "用户配置", color = Ink.muted)
                val handlerLabel = (row.handler.opt("statusMessage") as? String)?.takeIf { it.isNotBlank() }
                    ?: if (row.handler.optString("type") == "command") "命令检查"
                    else "MCP 工具检查"
                Text(handlerLabel.take(160), style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                Text(when (row.policy) { "block" -> "失败时阻断"; "continue" -> "失败时继续"; else -> "未知策略" })
                if (!row.managed) {
                    val editable = ready && enabled && row.version != null &&
                        requirements?.optBoolean("allowManagedHooksOnly") != true
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CodexSecondaryButton(onClick = { target = row to "continue" },
                            enabled = editable && row.policy != "continue") { Text("继续执行") }
                        CodexSecondaryButton(onClick = { target = row to "block" },
                            enabled = editable && row.canBlock && row.policy != "block") { Text("阻断操作") }
                    }
                    if (!row.canBlock) Text("此事件或异步检查只支持继续执行。", color = Ink.muted)
                }
            }
        }
    }
    target?.let { (row, next) ->
        CodexConfirmDialog("修改检查失败策略",
            "仅修改所选用户检查的失败处理方式，其他规则保留。生效情况以核心回读为准。",
            confirmLabel = "确认保存", confirmEnabled = enabled && ready && requirements?.optBoolean("allowManagedHooksOnly") != true && local.any {
                it.event == row.event && it.group == row.group && it.index == row.index && it.version == row.version
            }, onConfirm = {
                vm.saveEngineSetting("hooks." + row.event, row.change(next), "检查失败策略",
                    expectedVersion = row.version)
                target = null
            }, onDismiss = { target = null })
    }
}
