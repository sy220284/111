package io.github.sy220284.codexandroid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** MCP 动态参数表单：普通字段由 schema 渲染，复杂结构提供明确的专家编辑入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolCallSheet(
    vm: CodexViewModel, server: String, tool: String,
    metadata: JSONObject, onDismiss: () -> Unit,
    onOpenThreads: () -> Unit
) {
    val thread by vm.activeThread.collectAsState()
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val key = "mcpCall:" + server + ":" + tool
    val schema = metadata.optJSONObject("inputSchema")
    val properties = schema?.optJSONObject("properties")
    val names = properties?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
    val required = schema?.optJSONArray("required") ?: JSONArray()
    val requiredNames = (0 until required.length()).map { required.optString(it) }.toSet()
    val supported = schema != null &&
        schema.optString("type").let { it.isBlank() || it == "object" } &&
        requiredNames.all { it in names } && names.all { field ->
        properties?.optJSONObject(field)?.optString("type") in
            setOf("string", "integer", "number", "boolean")
    }
    val values = remember(server, tool) { mutableStateMapOf<String, String>() }
    var advanced by remember(server, tool) { mutableStateOf(!supported) }
    var argsText by remember(server, tool) { mutableStateOf("{}") }
    var confirming by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var submitted by remember(server, tool, thread) { mutableStateOf(false) }

    val arguments = if (advanced) {
        runCatching { JSONObject(argsText) }.getOrNull()
    } else {
        runCatching {
            val result = JSONObject()
            for (name in names) {
                val definition = properties?.optJSONObject(name) ?: continue
                val raw = values[name]?.trim().orEmpty()
                if (raw.isEmpty()) {
                    require(name !in requiredNames) { "尚未填写必填参数" }
                    continue
                }
                when (definition.optString("type")) {
                    "boolean" -> result.put(name, raw == "true")
                    "integer" -> result.put(name, raw.toLongOrNull()
                        ?: throw IllegalArgumentException("请输入有效整数"))
                    "number" -> result.put(name, raw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("请输入有效数值"))
                    "string" -> result.put(name, raw)
                }
            }
            result
        }.getOrNull()
    }

    val argumentProblem = McpToolArguments.problem(schema, arguments)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 690.dp)
            .verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("调用外部工具", style = MaterialTheme.typography.headlineSmall)
            Text(PresentationPolicy.meaningful(metadata.optString("title"))
                .ifEmpty { PresentationPolicy.skillName(tool) },
                style = MaterialTheme.typography.titleMedium)
            Text("服务：" + PresentationPolicy.meaningful(server).ifEmpty { "当前工具服务" },
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            PresentationPolicy.meaningful(metadata.optString("description")).takeIf {
                it.isNotEmpty()
            }?.let { Text(it, color = Ink.muted, style = MaterialTheme.typography.bodyMedium) }

            if (thread == null) {
                RequiresThreadPanel("MCP 工具调用", onOpenThreads)
            } else {
                if (names.isEmpty() && !advanced) {
                    Text("此工具不需要额外参数。", color = Ink.muted)
                }
                if (!advanced) {
                    for ((index, name) in names.withIndex()) {
                        val field = properties?.optJSONObject(name) ?: continue
                        val type = field.optString("type")
                        val title = PresentationPolicy.meaningful(field.optString("title"))
                            .ifEmpty {
                                PresentationPolicy.skillName(name).ifEmpty { "参数 ${index + 1}" }
                            }
                        val mandatory = name in requiredNames
                        val label = title + if (mandatory) "（必填）" else ""
                        val description = PresentationPolicy.meaningful(
                            field.optString("description"))
                        val selected = values[name].orEmpty()
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        if (description.isNotEmpty())
                            Text(description, color = Ink.muted,
                                style = MaterialTheme.typography.bodySmall)
                        when (type) {
                            "boolean" -> {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    WhiteSwitch(selected == "true",
                                        onCheckedChange = { values[name] = it.toString() })
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (selected == "true") "启用" else "关闭")
                                }
                            }
                            else -> {
                                val options = field.optJSONArray("enum")
                                if (options != null && options.length() > 0) {
                                    for (j in 0 until options.length()) {
                                        val option = options.optString(j)
                                        Row(Modifier.fillMaxWidth().clickable {
                                            values[name] = option
                                        }, verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected == option,
                                                onClick = { values[name] = option })
                                            Text(option)
                                        }
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = selected, onValueChange = { values[name] = it },
                                        label = { Text(label) }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        visualTransformation =
                                            if (field.optString("format") == "password")
                                                PasswordVisualTransformation()
                                            else VisualTransformation.None
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    if (!advanced) {
                        // Carry the visual form's exact typed arguments into expert editing.
                        argsText = arguments?.toString(2) ?: "{}"
                    } else {
                        // Rehydrate visual fields after edits to the JSON object.
                        val edited = runCatching { JSONObject(argsText) }.getOrNull()
                        if (edited != null) {
                            values.clear()
                            names.forEach { name ->
                                if (edited.has(name) && !edited.isNull(name))
                                    values[name] = edited.opt(name).toString()
                            }
                        }
                    }
                    advanced = !advanced
                }, enabled = supported) {
                    Icon(if (advanced) Icons.Outlined.ExpandLess
                        else Icons.Outlined.ExpandMore, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (advanced && supported) "返回表单"
                        else if (advanced) "复杂参数编辑"
                        else "高级参数编辑")
                }
                if (advanced) {
                    Text("此工具需要指定参数对象。仅在了解参数格式时使用高级编辑。",
                        color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(argsText, { argsText = it },
                        label = { Text("自定义参数（JSON）") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8)
                }
                if (argumentProblem != null) {
                    Text(argumentProblem,
                        color = Ink.red, style = MaterialTheme.typography.bodySmall)
                }
                CodexPrimaryButton(onClick = { confirming = true },
                    enabled = arguments != null && argumentProblem == null && key !in loading,
                    busy = key in loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 49.dp)) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("核对并调用工具")
                }
            }
            if (submitted && key in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (submitted) errors[key]?.let { ErrorText(it) }
            if (submitted) data[key]?.let { response ->
                val isError = response.optBoolean("isError")
                CodexNotice(
                    if (isError) "工具已返回错误结果，请检查输出内容。"
                    else "工具已返回执行结果。",
                    if (isError) NoticeTone.Warning else NoticeTone.Success
                )
                OutlinedButton(onClick = { showResult = true },
                    modifier = Modifier.fillMaxWidth()) { Text("阅读工具结果") }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
    if (confirming && thread != null && arguments != null && argumentProblem == null) {
        val preview = if (advanced) "已填写自定义参数"
        else names.mapNotNull { name ->
            val raw = values[name]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val field = properties?.optJSONObject(name)
            val title = PresentationPolicy.meaningful(field?.optString("title"))
                .ifEmpty { PresentationPolicy.skillName(name) }
            "$title：" + if (field?.optString("format") == "password") "已填写"
                else raw.take(150)
        }.joinToString("\n").ifEmpty { "无额外参数" }
        CodexConfirmDialog(
            title = "确认调用外部工具",
            message = "即将使用当前会话调用外部服务。操作可能影响外部数据，请核对用途和参数。",
            detail = "服务：$server\n工具：$tool\n$preview",
            confirmLabel = "确认调用",
            onConfirm = {
                submitted = true
                showResult = false
                vm.rpc(key, "mcpServer/tool/call", JSONObject()
                    .put("server", server).put("tool", tool)
                    .put("threadId", thread).put("arguments", arguments))
                confirming = false
            }, onDismiss = { confirming = false }
        )
    }
    if (showResult) data[key]?.let { response ->
        val contents = response.optJSONArray("content")
        val readable = if (contents != null) {
            (0 until contents.length()).mapNotNull {
                contents.optJSONObject(it)?.optString("text")
            }.filter { it.isNotBlank() }.joinToString("\n\n")
        } else ""
        val structured = response.optJSONObject("structuredContent")
        FullTextReaderDialog("工具执行结果",
            readable.ifEmpty {
                structured?.toString(2) ?: "外部工具没有返回可阅读的文本结果。"
            }) { showResult = false }
    }
}
