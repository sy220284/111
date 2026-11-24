package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
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
    var readerText by remember(name, marketName) { mutableStateOf<String?>(null) }
    var readerTitle by remember(name, marketName) { mutableStateOf("") }
    var readerProblem by remember(name, marketName) { mutableStateOf("") }
    val detail = data[key]?.optJSONObject("plugin")

    fun refresh() {
        if (name.isBlank()) return
        vm.refresh(key, "plugin/read", InspectionContracts.plugin(name, marketName, marketPath))
    }
    LaunchedEffect(name, marketName, marketPath) { refresh() }

    CodexDetailSheet(
        title = entry.optJSONObject("interface")?.optString("displayName")?.ifBlank { name } ?: name,
        onDismiss = onDismiss, startExpanded = true,
        subtitle = "安装或启用之前查看实际提供的能力和来源。"
    ) {
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CodexIcon(CodexGlyph.Extension, null, tint = Ink.copper)
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
            val originalDescription = PresentationPolicy.meaningful(
                detail?.optString("description")?.ifBlank {
                    summary.optJSONObject("interface")?.optString("shortDescription").orEmpty()
                } ?: entry.optJSONObject("interface")?.optString("shortDescription").orEmpty()
            )
            val chineseSummary = if (originalDescription.any { it in '\u4e00'..'\u9fff' })
                originalDescription.take(140)
            else "插件功能可在下方能力清单查看。"
            Text(chineseSummary, color = Ink.muted,
                style = MaterialTheme.typography.bodyMedium)
            if (originalDescription.isNotEmpty() && originalDescription != chineseSummary) {
                TextButton(onClick = {
                    readerTitle = "插件原始说明"
                    readerText = originalDescription
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("查看插件原始说明")
                }
            }
            OutlinedButton(onClick = { refresh() }, enabled = name.isNotBlank() && key !in loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                CodexIcon(CodexGlyph.Retry, null)
                Spacer(Modifier.width(7.dp))
                Text("刷新插件详情")
            }
        }
        if (key in loading) AtlasLoadingResults("正在读取插件详情", placeholderRows = 2)
        errors[key]?.let { ErrorText(it) }
        if (detail == null && key !in loading && key !in errors) {
            CodexEmptyState("等待详情", "由 Codex 核心读取此插件的能力清单。")
        }
        detail?.let { info ->
            for ((field, label, icon) in listOf(
                Triple("skills", "提供的技能", CodexGlyph.School),
                Triple("mcpServers", "工具服务", CodexGlyph.Hub),
                Triple("apps", "连接应用", CodexGlyph.Apps),
                Triple("hooks", "运行钩子", CodexGlyph.Bolt),
                Triple("appTemplates", "应用模板", CodexGlyph.Widgets),
                Triple("scheduledTasks", "定时能力声明", CodexGlyph.TaskRunning)
            )) {
                val entries = info.optJSONArray(field) ?: continue
                if (entries.length() == 0) continue
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CodexIcon(icon, null, tint = Ink.copper)
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
                            .ifBlank { "第 ${i + 1} 项能力" }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CodexIcon(CodexGlyph.TaskComplete, null, tint = Ink.jade,
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
        }
        if (readerProblem.isNotBlank()) ErrorText(readerProblem)
        Spacer(Modifier.height(12.dp))
    }
    readerText?.let {
        FullTextReaderDialog(readerTitle, it) { readerText = null }
    }
}
