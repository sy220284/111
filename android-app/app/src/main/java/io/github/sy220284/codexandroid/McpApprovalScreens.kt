package io.github.sy220284.codexandroid

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun McpElicitationRequestCard(vm: CodexViewModel, prompt: ServerPrompt) {
    val context = LocalContext.current
    val params = prompt.params
    val kind = params.optString("type")
    val schema = params.optJSONObject("requestedSchema")
    val fields = schema?.optJSONObject("properties")
    val required = schema?.optJSONArray("required") ?: JSONArray()
    val answers = remember(prompt.id.toString()) { mutableStateMapOf<String, String>() }
    val isForm = kind in listOf("form", "openai/form", "openaiForm") && fields != null
    val urlReady = kind == "url" && (params.optString("url").startsWith("https://") ||
        params.optString("url").startsWith("http://127.0.0.1:"))
    val formError = if (isForm) runCatching {
        McpElicitationContract.respond(params, "accept", answers.toMap())
    }.exceptionOrNull()?.message else null
    val canSubmit = (isForm && formError == null) || urlReady
    var confirmation by remember(prompt.id.toString()) { mutableStateOf(false) }

    MaterialPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Extension, null, tint = Ink.copper)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("MCP 服务请求用户操作", style = MaterialTheme.typography.titleMedium)
                Text("来源：" + params.optString("serverName"), color = Ink.muted,
                    style = MaterialTheme.typography.labelMedium)
            }
            StatusPill("待确认", false)
        }
        DividerLine()
        Text(params.optString("message").ifBlank { params.optString("title") },
            style = MaterialTheme.typography.bodyMedium)
        if (isForm && fields != null) {
            for (name in fields.keys().asSequence().toList()) {
                val field = fields.optJSONObject(name) ?: continue
                val type = field.optString("type")
                val mandatory = (0 until required.length()).any { required.optString(it) == name }
                val title = field.optString("title").ifBlank { name }
                val label = title + if (mandatory) " *" else ""
                Text(label, style = MaterialTheme.typography.titleMedium, color = Ink.parchment)
                field.optString("description").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                }
                when (type) {
                    "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        val checked = answers[name] == "true"
                        WhiteSwitch(checked = checked, onCheckedChange = { answers[name] = it.toString() })
                        Spacer(Modifier.width(10.dp))
                        Text(if (checked) "允许" else "关闭", color = Ink.muted)
                    }
                    "string" -> {
                        val options = field.optJSONArray("enum")
                        if (options != null) {
                            for (index in 0 until options.length()) {
                                val value = options.optString(index)
                                Row(Modifier.fillMaxWidth().clickable { answers[name] = value },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = answers[name] == value, onClick = {
                                        answers[name] = value
                                    })
                                    Text(value)
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = answers[name] ?: "",
                                onValueChange = { answers[name] = it },
                                label = { Text(label) },
                                modifier = Modifier.fillMaxWidth().testTag("elicitation-field-" + name),
                                visualTransformation = if (field.optString("format") == "password") {
                                    PasswordVisualTransformation()
                                } else VisualTransformation.None
                            )
                        }
                    }
                    "integer", "number" -> OutlinedTextField(
                        value = answers[name] ?: "",
                        onValueChange = { answers[name] = it },
                        label = { Text(if (type == "integer") "整数" else "数值") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    "array" -> {
                        val choices = field.optJSONObject("items")?.optJSONArray("enum")
                        if (choices != null) {
                            for (index in 0 until choices.length()) {
                                val choice = choices.optString(index)
                                val selected = try {
                                    val array = JSONArray(answers[name] ?: "[]")
                                    (0 until array.length()).any { array.optString(it) == choice }
                                } catch (failure: Exception) { false }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = selected, onCheckedChange = { on ->
                                        val old = try {
                                            JSONArray(answers[name] ?: "[]")
                                        } catch (failure: Exception) { JSONArray() }
                                        val entries = (0 until old.length()).map { old.optString(it) }.toMutableList()
                                        if (on && choice !in entries) entries += choice
                                        if (!on) entries.remove(choice)
                                        answers[name] = JSONArray(entries).toString()
                                    })
                                    Text(choice)
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = answers[name] ?: "[]",
                                onValueChange = { answers[name] = it },
                                label = { Text("JSON 数组") }, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    else -> Text("当前服务使用了尚未支持的表单字段，请联系服务提供方。", color = Ink.red)
                }
            }
        } else if (kind == "url") {
            val link = params.optString("url")
            Text("请在浏览器完成外部服务验证，返回后再确认。", color = Ink.muted)
            if (link.startsWith("https://") || link.startsWith("http://127.0.0.1:")) {
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) {
                    Icon(Icons.Outlined.OpenInBrowser, null); Text("打开授权页面")
                }
            } else ErrorText("服务端提供了不安全的跳转地址")
        } else {
            Text("当前类型需设备级专门验证，暂可拒绝或取消。",
                color = Ink.muted)
            Text("请确认请求来源，或联系服务提供方检查授权方式。",
                style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.answerApproval(prompt, ApprovalAction.Decline) }) { Text("拒绝") }
            TextButton(onClick = { vm.answerApproval(prompt, ApprovalAction.Cancel) }) { Text("取消") }
            if (isForm || urlReady) {
                OutlinedButton(onClick = { confirmation = true },
                    enabled = canSubmit,
                    modifier = Modifier.testTag("elicitation-submit")
                        .explainDisabled(!canSubmit, "请填写必填项目并检查格式")) {
                    Text("核对并提交")
                }
            }
        }
        if (isForm && formError != null) {
            Text("填写完整并核对格式后，即可提交。",
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (confirmation) {
        CodexConfirmDialog(
            title = "向 MCP 服务提交确认",
            message = if (kind == "url") "确认外部服务授权已完成。" else "即将提交已填写的表单字段。",
            detail = "服务：" + params.optString("serverName") + "\n提交字段数：" + answers.size,
            confirmLabel = "确认提交",
            onConfirm = {
                vm.answerMcpElicitation(prompt, answers.toMap())
                confirmation = false
            },
            onDismiss = { confirmation = false }
        )
    }
}
