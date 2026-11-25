package io.github.sy220284.codexandroid

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONObject

@Composable
internal fun DomesticCatalogSheet(vm: CodexViewModel, preset: DomesticModelPreset, id: String, dismiss: () -> Unit) {
    val key = "domesticCatalog:$id"
    val probeKey = "domesticProbe:$id"
    var confirmModel by remember(id) { mutableStateOf<String?>(null) }
    var testedModel by remember(id) { mutableStateOf<String?>(null) }
    val data by vm.data.collectEntries(key, probeKey)
    val loading by vm.loading.collectAsState()
    val errors by vm.errors.collectAsState()
    val engineStatus by vm.bridge.status.collectAsState()
    val connected = engineStatus == EngineStatus.Connected
    val probeRunning = loading.any { it.startsWith("domesticProbe:") }
    val providers by vm.data.collectEntries("providers")
    val endpoint = records(providers["providers"]).firstOrNull { it.optString("id") == id }?.optString("baseUrl").orEmpty()
    val live = preset.liveCatalog(endpoint)
    fun refresh() {
        vm.refresh(key, "model/list", ComposerProviderScope.request(id, forceRefresh = live).put("includeHidden", true))
    }
    LaunchedEffect(id, endpoint, engineStatus) {
        confirmModel = null
        testedModel = null
        if (connected) refresh()
    }
    LaunchedEffect(testedModel, loading.contains(probeKey), errors[probeKey]) {
        if (connected && testedModel != null && probeKey !in loading && errors[probeKey] != null) refresh()
    }
    confirmModel?.let { model ->
        CodexConfirmDialog(title = "真实模型能力验证",
            message = "最多发送两轮固定测试推理请求，每轮限制输出量，可能产生模型费用。不上传对话、项目文件或执行真实工具。",
            detail = model, confirmLabel = "开始验证",
            onConfirm = {
                confirmModel = null
                testedModel = model
                if (connected && !probeRunning) {
                    vm.rpc(probeKey, "modelProvider/model/verify", DomesticProbeContract.params(id, model, confirmed = true)) {
                        refresh()
                        vm.refresh(ComposerProviderScope.catalogKey(id), "model/list", ComposerProviderScope.request(id))
                        vm.refresh("models", "model/list")
                    }
                }
            }, onDismiss = { confirmModel = null })
    }
    CodexDetailSheet(title = preset.name + " · 模型目录", startExpanded = true, onDismiss = dismiss) {
        Text(if (live) "实时账号目录；目录授权成功仍需通过真实任务验证。" else "本地文档候选；暂未实现该账号实时目录查询。", color = Ink.muted)
        CodexSecondaryButton(onClick = { refresh() }, enabled = connected && key !in loading && !probeRunning) { Text("刷新目录") }
        when {
            !connected -> Text("内核未连接，连接后重新读取实时目录", color = Ink.muted)
            key in loading -> AtlasOperationProgress("正在读取模型目录")
            errors[key] != null -> ErrorText(errors.getValue(key))
            data[key] == null -> Text("尚未取得目录结果", color = Ink.muted)
            else -> {
                val models = records(data[key])
                Text(if (models.isEmpty()) "当前目录没有模型" else if (live) "实时读取成功 · ${models.size} 个模型" else "候选目录 · ${models.size} 个模型", color = Ink.muted)
                models.forEach { model ->
                    val modelId = model.optString("id").ifBlank { model.optString("model") }
                    Text(modelId)
                    Text(model.optString("description").ifBlank {
                        if (model.optBoolean("hidden")) "新发现 · 协议能力待核验" else if (live) "目录可选 · 任务待验收" else "候选 · 授权待验证"
                    }, color = Ink.muted)
                    if (live) {
                        CodexSecondaryButton(onClick = { confirmModel = modelId },
                            enabled = modelId.isNotBlank() && connected && !probeRunning && key !in loading) { Text("验证模型工具调用") }
                        if (testedModel == modelId) {
                            when {
                                probeKey in loading -> AtlasOperationProgress("正在验证双轮工具回传")
                                errors[probeKey] != null -> ErrorText(errors.getValue(probeKey))
                                data[probeKey]?.optBoolean("modelIdentityConfirmed") == true ->
                                    Text("双轮验证通过 · 模型身份已确认 · " + if (data[probeKey]?.optBoolean("usageReported") == true) "用量回报正常" else "未回报用量", color = Ink.muted)
                                data[probeKey] != null -> Text("工具回传通过；服务商未确认实际模型身份，不新增模型准入", color = Ink.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}
