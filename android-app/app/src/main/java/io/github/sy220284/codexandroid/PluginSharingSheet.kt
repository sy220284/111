package io.github.sy220284.codexandroid

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import java.io.File

@Composable
internal fun PluginSharingSheet(
    vm: CodexViewModel, initialPath: String, onChanged: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val operation = rememberCapabilityOperation(vm)
    var shares by remember { mutableStateOf<List<JSONObject>?>(null) }
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    var remoteId by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var sharedId by remember { mutableStateOf("") }
    var entry by remember { mutableStateOf<JSONObject?>(null) }
    var editingTargets by remember { mutableStateOf(false) }
    var targetDraft by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("PRIVATE") }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var readyToInstall by remember { mutableStateOf<JSONObject?>(null) }
    fun id(item: JSONObject): String = item.optJSONObject("plugin")?.let { plugin ->
        plugin.value("remotePluginId").ifBlank {
            plugin.optJSONObject("shareContext")?.value("remotePluginId").orEmpty()
        }
    }.orEmpty()
    suspend fun refresh() {
        val selected = entry?.let(::id)
        val rows = vm.capabilityRequest("plugin/share/list", timeout = 90_000).objects()
        shares = rows
        if (selected != null) entry = rows.firstOrNull { id(it) == selected }
    }
    fun validatedPath(): String {
        val directory = File(path.trim())
        require(directory.isAbsolute) { "请填写插件目录的完整路径" }
        val source = directory.canonicalFile
        val root = listOf(File(vm.workingDirectory).canonicalFile, context.filesDir.canonicalFile)
        require(root.any { source != it && source.toPath().startsWith(it.toPath()) } &&
            source.isDirectory && source.canRead()) { "请选择应用内或 Download/Codex 工作区中的可读插件目录" }
        require(File(source, ".codex-plugin/plugin.json").isFile) { "插件目录缺少 .codex-plugin/plugin.json" }
        return source.path
    }
    fun edit(item: JSONObject) {
        val config = item.optJSONObject("plugin")?.optJSONObject("shareContext")
        visibility = config?.value("discoverability")?.takeIf { it in setOf("PRIVATE", "UNLISTED", "LISTED") } ?: "PRIVATE"
        targetDraft = config?.objects("sharePrincipals").orEmpty().filter {
            it.value("principalType") in setOf("user", "group") && it.value("role") in setOf("reader", "editor")
        }.joinToString("\n") { "${it.value("principalType")}:${it.value("principalId")}:${it.value("role")}" }
        editingTargets = true
    }
    LaunchedEffect(vm) { operation.launch { refresh() } }

    CodexDetailSheet("插件共享", onDismiss = { if (!operation.busy) onDismiss() },
        subtitle = "发布本地插件、管理访问权限，或下载别人共享的插件源。") {
        CapabilityFeedback(operation, "正在同步插件共享")
        if (link.isNotBlank() || sharedId.isNotBlank()) {
            SelectionContainer {
                Column {
                    if (sharedId.isNotBlank()) Text("插件标识 $sharedId", style = MaterialTheme.typography.bodySmall)
                    if (link.isNotBlank()) Text(link, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (sharedId.isNotBlank()) CodexTextAction(onClick = {
                operation.launch {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                            PluginSharingContract.shareDetails(sharedId, link)), "分享插件"))
                }
            }) { Text("分享插件信息") }
        }
        if (entry == null) {
            Text("我共享的插件", style = MaterialTheme.typography.titleMedium)
            if (shares?.isEmpty() == true) Text("暂无共享的插件", color = Ink.muted)
            shares.orEmpty().forEach { item ->
                val name = item.optJSONObject("plugin")?.value("name").orEmpty()
                CodexTextAction(onClick = {
                    entry = item
                    editingTargets = false
                    sharedId = id(item)
                    link = item.optJSONObject("plugin")?.optJSONObject("shareContext")?.value("shareUrl").orEmpty()
                }, enabled = !operation.busy && id(item).isNotBlank()) { Text(name.ifBlank { id(item) }) }
            }
            CodexTextAction(onClick = { operation.launch { refresh() } }, enabled = !operation.busy) {
                Text("刷新共享列表")
            }
            HorizontalDivider()
            Text("共享本地插件", style = MaterialTheme.typography.titleMedium)
            CodexInputField(path, { path = it }, label = { Text("插件目录完整路径") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            CodexSecondaryButton(onClick = { confirmation = "publish" },
                enabled = path.isNotBlank() && !operation.busy) { Text("创建私有共享") }
            HorizontalDivider()
            Text("下载别人共享的插件", style = MaterialTheme.typography.titleMedium)
            CodexInputField(remoteId, { remoteId = it }, label = { Text("共享信息或插件标识") },
                maxLines = 3, modifier = Modifier.fillMaxWidth())
            CodexSecondaryButton(onClick = { confirmation = "checkout" },
                enabled = remoteId.isNotBlank() && !operation.busy) { Text("下载插件源") }
            readyToInstall?.let { downloaded ->
                Text("已下载 ${downloaded.value("pluginName")}", color = Ink.muted)
                SelectionContainer { Text(downloaded.value("pluginPath"), style = MaterialTheme.typography.bodySmall) }
                CodexTextAction(onClick = { confirmation = "install" }, enabled = !operation.busy) { Text("安装已下载插件") }
            }
        } else entry?.let { item ->
            val plugin = item.optJSONObject("plugin") ?: return@let
            Text(plugin.value("name").ifBlank { id(item) }, style = MaterialTheme.typography.titleMedium)
            val access = plugin.optJSONObject("shareContext")?.value("discoverability").orEmpty()
            if (access.isNotBlank()) Text("当前范围  ${shareVisibilityLabel(access)}", color = Ink.muted)
            if (editingTargets) {
                Text("访问范围", style = MaterialTheme.typography.titleMedium)
                AtlasCompactChoice(visibility, listOf("PRIVATE" to "指定对象", "UNLISTED" to "工作区链接") +
                    (if (access == "LISTED") listOf("LISTED" to "工作区可发现") else emptyList()),
                    onSelect = { visibility = it }, enabled = !operation.busy, tagPrefix = "plugin-share-visibility-")
                CodexInputField(targetDraft, { targetDraft = it }, label = { Text("共享对象和权限") },
                    enabled = !operation.busy, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Text("每行填写 user:账号标识:reader 或 group:群组标识:editor。保存会覆盖现有对象列表。",
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                CodexSecondaryButton(onClick = { confirmation = "targets" }, enabled = !operation.busy) {
                    Text("保存访问权限")
                }
            } else CodexSecondaryButton(onClick = { edit(item) }, enabled = !operation.busy) { Text("管理访问权限") }
            val local = item.value("localPluginPath")
            if (local.isNotBlank()) {
                CodexTextAction(onClick = { path = local; confirmation = "update" },
                    enabled = !operation.busy) { Text("发布本地更新") }
            }
            CodexTextAction(onClick = { confirmation = "delete" }, enabled = !operation.busy) {
                Text("撤销共享", color = Ink.red)
            }
            CodexTextAction(onClick = { entry = null; editingTargets = false; sharedId = ""; link = "" },
                enabled = !operation.busy) { Text("返回共享列表") }
        }
    }

    confirmation?.let { action ->
        val title = when (action) {
            "publish" -> "创建私有共享"
            "update" -> "发布插件更新"
            "targets" -> "更新访问权限"
            "delete" -> "撤销插件共享"
            "checkout" -> "下载插件源"
            else -> "安装已下载插件"
        }
        val message = when (action) {
            "publish" -> "将上传此目录中的插件。创建后可以为指定账号或群组授权。"
            "update" -> "将上传所选目录中的插件版本，沿用当前访问权限。"
            "targets" -> "访问范围将设为「${shareVisibilityLabel(visibility)}」，对象列表将以表单内容为准。"
            "delete" -> "撤销后，已发出的链接将失效，设备上已有的副本会保留。"
            "checkout" -> "将下载此插件的源文件，下载后可以核对来源再安装。"
            else -> "将安装下载的插件及其提供的能力。请确认来源可信。"
        }
        CodexConfirmDialog(title, message,
            detail = when (action) {
                "publish", "update" -> path
                "checkout" -> remoteId
                "install" -> readyToInstall?.value("pluginPath").orEmpty()
                else -> entry?.optJSONObject("plugin")?.value("name").orEmpty()
            }, confirmLabel = title, destructive = action == "delete",
            onConfirm = {
                confirmation = null
                operation.launch {
                    when (action) {
                        "publish", "update" -> {
                            val params = JSONObject().put("pluginPath", validatedPath())
                            if (action == "publish") params.put("discoverability", "PRIVATE")
                            else params.put("remotePluginId", PluginSharingContract.remoteId(entry?.let(::id).orEmpty()))
                            val response = vm.capabilityRequest("plugin/share/save", params, 120_000)
                            sharedId = response.getString("remotePluginId")
                            link = response.value("shareUrl")
                            operation.receipt = if (action == "publish") "私有共享已创建" else "插件更新已发布"
                            onChanged()
                            refresh()
                            entry = shares.orEmpty().firstOrNull { id(it) == sharedId }
                        }
                        "targets" -> {
                            val response = vm.capabilityRequest("plugin/share/updateTargets", JSONObject()
                                .put("remotePluginId", PluginSharingContract.remoteId(entry?.let(::id).orEmpty()))
                                .put("discoverability", visibility)
                                .put("shareTargets", PluginSharingContract.targets(targetDraft)))
                            visibility = response.getString("discoverability")
                            editingTargets = false
                            operation.receipt = "访问权限已更新"
                            onChanged()
                            refresh()
                        }
                        "delete" -> {
                            vm.capabilityRequest("plugin/share/delete", JSONObject()
                                .put("remotePluginId", PluginSharingContract.remoteId(entry?.let(::id).orEmpty())))
                            entry = null
                            sharedId = ""
                            link = ""
                            editingTargets = false
                            operation.receipt = "共享已撤销"
                            onChanged()
                            refresh()
                        }
                        "checkout" -> {
                            readyToInstall = vm.capabilityRequest("plugin/share/checkout", JSONObject()
                                .put("remotePluginId", PluginSharingContract.remoteId(remoteId)), 120_000)
                            operation.receipt = "插件源已下载"
                            onChanged()
                        }
                        "install" -> {
                            val downloaded = checkNotNull(readyToInstall)
                            val response = vm.capabilityRequest("plugin/install", JSONObject()
                                .put("pluginName", downloaded.getString("pluginName"))
                                .put("marketplacePath", downloaded.getString("marketplacePath")), 120_000)
                            readyToInstall = null
                            operation.receipt = if (response.objects("appsNeedingAuth").isEmpty()) "共享插件已安装"
                            else "共享插件已安装。请到连接应用完成所需的账号授权。"
                            onChanged()
                        }
                    }
                }
            }, onDismiss = { confirmation = null })
    }
}

private fun shareVisibilityLabel(visibility: String): String = when (visibility) {
    "PRIVATE" -> "指定对象"
    "UNLISTED" -> "工作区链接"
    "LISTED" -> "工作区可发现"
    else -> visibility
}
