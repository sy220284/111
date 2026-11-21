package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Read-only marketplace detail: inspect capabilities before installing a plugin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDetailSheet(
    marketplace: JSONObject,
    entry: JSONObject,
    vm: CodexViewModel,
    onDismiss: () -> Unit
) {
    val name = entry.optString("name")
    val marketName = marketplace.optString("name")
    val marketPath = marketplace.optString("path")
    val key = "pluginDetail:" + marketName + ":" + name
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    var rawOpen by remember(name, marketName) { mutableStateOf(false) }
    var readerText by remember(name, marketName) { mutableStateOf<String?>(null) }
    var readerTitle by remember(name, marketName) { mutableStateOf("") }
    var readerProblem by remember(name, marketName) { mutableStateOf("") }
    val detail = data[key]?.optJSONObject("plugin")

    fun refresh() {
        if (name.isBlank()) return
        vm.refresh(key, "plugin/read", InspectionContracts.plugin(name, marketName, marketPath))
    }
    LaunchedEffect(name, marketName, marketPath) { refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed,
        contentColor = Ink.parchment
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            SectionIntro("PLUGIN / DETAIL", entry.optJSONObject("interface")?.optString("displayName")
                ?.ifBlank { name } ?: name, "安装或启用之前查看实际提供的能力和来源。")
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Extension, null, tint = Ink.copper)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("插件信息", style = MaterialTheme.typography.titleMedium)
                        Text(marketName.ifBlank { "本地插件源" },
                            color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    val summary = detail?.optJSONObject("summary") ?: entry
                    StatusPill(if (summary.optBoolean("installed")) "已安装" else "未安装",
                        summary.optBoolean("installed"))
                }
                val summary = detail?.optJSONObject("summary") ?: entry
                val version = summary.optString("version")
                if (version.isNotBlank()) Text("版本 " + version,
                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                Text(detail?.optString("description")?.ifBlank { summary.optJSONObject("interface")
                    ?.optString("shortDescription").orEmpty() }
                    ?: entry.optJSONObject("interface")?.optString("shortDescription").orEmpty(),
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { refresh() }, enabled = name.isNotBlank() && key !in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(7.dp))
                    Text("刷新插件详情")
                }
            }
            if (key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[key]?.let { ErrorText(it) }
            if (detail == null && key !in loading && key !in errors) {
                CodexEmptyState("等待详情", "由 Codex 核心读取此插件的能力清单。")
            }
            detail?.let { info ->
                for ((field, label, icon) in listOf(
                    Triple("skills", "提供的技能", Icons.Outlined.School),
                    Triple("mcpServers", "工具服务", Icons.Outlined.Hub),
                    Triple("apps", "连接应用", Icons.Outlined.Apps),
                    Triple("hooks", "运行钩子", Icons.Outlined.Bolt),
                    Triple("appTemplates", "应用模板", Icons.Outlined.Widgets),
                    Triple("scheduledTasks", "定时能力声明", Icons.Outlined.Schedule)
                )) {
                    val entries = info.optJSONArray(field) ?: continue
                    if (entries.length() == 0) continue
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, tint = Ink.copper)
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            Text(entries.length().toString(), color = Ink.muted)
                        }
                        for (i in 0 until entries.length()) {
                            val value = entries.opt(i)
                            val obj = value as? JSONObject
                            val title = obj?.optString("name").orEmpty()
                                .ifBlank { obj?.optString("displayName").orEmpty() }
                                .ifBlank { obj?.optString("id").orEmpty() }
                                .ifBlank { value?.toString().orEmpty() }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.CheckCircleOutline, null, tint = Ink.jade,
                                    modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f))
                                val skillId = obj?.optString("name").orEmpty()
                                val remoteId = entry.optString("id")
                                if (field == "skills" && skillId.isNotBlank() &&
                                    remoteId.isNotBlank() && marketName.isNotBlank()) {
                                    val skillKey = "pluginSkill:" + remoteId + ":" + skillId
                                    TextButton(
                                        onClick = {
                                            readerTitle = title
                                            readerText = null
                                            readerProblem = ""
                                            vm.refresh(skillKey, "plugin/skill/read", JSONObject()
                                                .put("remoteMarketplaceName", marketName)
                                                .put("remotePluginId", remoteId)
                                                .put("skillName", skillId),
                                                onSuccess = { response ->
                                                    val contents = response.optString("contents")
                                                    if (contents.isBlank())
                                                        readerProblem = "当前内核未返回该技能的正文内容"
                                                    else readerText = contents
                                                })
                                        },
                                        enabled = skillKey !in loading
                                    ) { Text(if (skillKey in loading) "读取中" else "阅读") }
                                    errors[skillKey]?.let { ErrorText(it) }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { rawOpen = !rawOpen }) {
                    Text(if (rawOpen) "收起完整协议记录" else "查看完整协议记录")
                }
                CodexReveal(rawOpen) {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        val raw = info.toString(2)
                        SelectionContainer {
                            Text(raw.take(14000), fontFamily = FontFamily.Monospace,
                                color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (raw.length > 14000) {
                            OutlinedButton(onClick = {
                                readerTitle = "插件完整协议记录"
                                readerText = raw
                            }) { Text("查看全文") }
                        }
                    }
                }
            }
            if (readerProblem.isNotBlank()) ErrorText(readerProblem)
            Spacer(Modifier.height(12.dp))
        }
    }
    readerText?.let {
        FullTextReaderDialog(readerTitle, it) { readerText = null }
    }
}
