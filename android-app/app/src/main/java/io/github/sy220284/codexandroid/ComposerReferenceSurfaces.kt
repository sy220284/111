package io.github.sy220284.codexandroid

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

@Composable
internal fun ComposerReferenceStrip(references: List<ComposerReference>, onRemove: (ComposerReference) -> Unit) {
    if (references.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("composer-references"),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        references.forEach { reference ->
            Surface(shape = RoundedCornerShape(12.dp), color = Ink.recessed) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(if (reference.kind == "skill") CodexGlyph.School else CodexGlyph.Description, null,
                        modifier = Modifier.size(20.dp), tint = Ink.muted)
                    Text(reference.name, modifier = Modifier.widthIn(max = 126.dp).padding(start = 6.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    CodexIconAction(onClick = { onRemove(reference) }) { CodexIcon(CodexGlyph.Close, "移除引用 " + reference.name) }
                }
            }
        }
    }
}
internal data class ComposerFileSearchResult(val files: List<ComposerReference>, val more: Boolean = false, val error: String? = null)
internal object ComposerFileSearch {
    suspend fun search(root: File, workspace: File, query: String): ComposerFileSearchResult = withContext(Dispatchers.IO) {
        try {
            val base = WorkspaceSelectionContract.validate(root, workspace)
            val result = mutableListOf<ComposerReference>()
            Files.walk(base.toPath()).use { stream ->
                val iterator = stream.iterator()
                while (iterator.hasNext() && result.size < 101) {
                    coroutineContext.ensureActive()
                    val path = iterator.next()
                    if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                    val file = path.toFile().canonicalFile
                    if (!file.path.startsWith(base.path + File.separator)) continue
                    val relative = file.relativeTo(base).path
                    if (relative.split(File.separatorChar).any { it == ".git" }) continue
                    if (relative.contains(query.trim(), ignoreCase = true))
                        result += ComposerReference("file", file.name, file.absolutePath)
                }
            }
            ComposerFileSearchResult(result.take(100), result.size > 100)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { ComposerFileSearchResult(emptyList(), error = failure.message ?: "无法读取项目文件") }
    }
}

@Composable
internal fun ComposerFileReferenceSheet(vm: CodexViewModel, workspace: String, initialQuery: String,
                                        onSelect: (ComposerReference) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(initialQuery) }
    val result by produceState<ComposerFileSearchResult?>(null, workspace, query) {
        value = null
        delay(150)
        value = ComposerFileSearch.search(File(vm.workingDirectory), File(workspace), query)
    }
    CodexDetailSheet(title = "引用项目文件", onDismiss = onDismiss, subtitle = PresentationPolicy.workspaceName(workspace)) {
        AtlasSearchField(query, { query = it }, "搜索文件名称或路径", onClear = { query = "" },
            modifier = Modifier.testTag("composer-reference-search"))
        val current = result
        if (current == null) AtlasOperationProgress("正在搜索文件")
        else if (current.error != null) ErrorText(current.error)
        else {
            if (current.files.isEmpty()) Text("没有匹配的项目文件", color = Ink.muted)
            if (current.more) Text("显示前 100 项，请缩小搜索范围", color = Ink.muted)
            current.files.forEach { file ->
                AtlasSettingsActionRow(file.name, File(file.path).relativeTo(File(workspace).canonicalFile).path,
                    CodexGlyph.Description, onClick = { onSelect(file) }, outlined = false)
            }
        }
    }
}

@Composable
internal fun ComposerSkillSheet(vm: CodexViewModel, workspace: String, onSelect: (ComposerReference) -> Unit, onDismiss: () -> Unit) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val key = "composerSkills"
    fun reload() = vm.refresh(key, "skills/list", CatalogRequestContract.workspace(workspace))
    LaunchedEffect(workspace, status) { if (status == EngineStatus.Connected) reload() }
    val skills = records(data[key]).flatMap { group ->
        group.optJSONArray("skills")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
    }.filter { it.optBoolean("enabled") && it.optString("path").isNotBlank() && it.optString("name").isNotBlank() }
        .distinctBy { it.optString("path") }
    var query by remember { mutableStateOf("") }
    CodexDetailSheet(title = "选择技能", onDismiss = onDismiss, subtitle = "仅显示当前工作区已启用的技能") {
        if (status != EngineStatus.Connected) Text("连接内核后可选择技能", color = Ink.muted)
        else if (key in loading) AtlasOperationProgress("正在读取技能")
        else if (errors[key] != null) {
            ErrorText(PresentationPolicy.readableError(errors.getValue(key)))
            CodexSecondaryButton(onClick = { reload() }) { Text("重新读取") }
        } else if (skills.isEmpty()) Text("当前没有已启用技能", color = Ink.muted)
        else {
            AtlasSearchField(query, { query = it }, "搜索技能", onClear = { query = "" })
            skills.filter { it.optString("name").contains(query, true) ||
                it.optJSONObject("interface")?.optString("displayName")?.contains(query, true) == true }.forEach { skill ->
                val name = skill.optString("name")
                AtlasSettingsActionRow(skill.optJSONObject("interface")?.optString("displayName").orEmpty().ifBlank { name },
                    skill.optString("description").take(100), CodexGlyph.School, outlined = false,
                    onClick = { onSelect(ComposerReference("skill", name, skill.optString("path"))) })
            }
        }
    }
}

@Composable
internal fun ComposerContextUsageSheet(usage: org.json.JSONObject?, onCompact: (() -> Unit)?, onDismiss: () -> Unit) {
    val window = usage?.optLong("modelContextWindow", 0) ?: 0L
    val last = usage?.optJSONObject("last")
    val used = last?.optLong("totalTokens", -1) ?: -1L
    CodexDetailSheet(title = "上下文容量", onDismiss = onDismiss) {
        if (window > 0 && used >= 0) {
            val percent = (used.toDouble() * 100 / window).coerceIn(0.0, 100.0).toInt()
            Text("约已使用 ${percent}%", style = MaterialTheme.typography.headlineSmall)
            Text("最近一次模型调用：$used / $window 个 token", color = Ink.muted)
            if (percent >= 85) CodexNotice("当前上下文接近容量上限，可考虑整理上下文。", NoticeTone.Warning)
        } else Text("尚未收到当前会话的有效容量数据", color = Ink.muted)
        if (onCompact != null) CodexSecondaryButton(onClick = onCompact) { Text("打开上下文整理") }
    }
}
