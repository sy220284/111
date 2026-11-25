package io.github.sy220284.codexandroid

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.json.JSONObject

@Composable
internal fun DomesticCatalogSheet(vm: CodexViewModel, preset: DomesticModelPreset, id: String, dismiss: () -> Unit) {
    val key = "domesticCatalog:$id"
    val data by vm.data.collectEntries(key)
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val live = preset.catalog != "manual"
    fun refresh() {
        vm.refresh(key, "model/list", JSONObject().put("modelProvider", id).put("includeHidden", true))
    }
    LaunchedEffect(id) { refresh() }
    CodexDetailSheet(title = preset.name + " · 模型目录", startExpanded = true, onDismiss = dismiss) {
        Text(if (live) "实时账号目录；目录授权成功仍需通过真实任务验证。" else "本地文档候选；暂未实现该账号实时目录查询。", color = Ink.muted)
        CodexSecondaryButton(onClick = { refresh() }, enabled = key !in loading) { Text("刷新目录") }
        when {
            key in loading -> AtlasOperationProgress("正在读取模型目录")
            errors[key] != null -> ErrorText(errors.getValue(key))
            data[key] == null -> Text("尚未取得目录结果", color = Ink.muted)
            else -> {
                val models = records(data[key])
                Text(if (models.isEmpty()) "当前目录没有模型" else if (live) "实时读取成功 · ${models.size} 个模型" else "候选目录 · ${models.size} 个模型", color = Ink.muted)
                models.forEach { model ->
                    Text(model.optString("id").ifBlank { model.optString("model") })
                    Text(if (model.optBoolean("hidden")) "新发现 · 协议能力待核验" else if (live) "目录可选 · 任务待验收" else "候选 · 授权待验证", color = Ink.muted)
                }
            }
        }
    }
}
