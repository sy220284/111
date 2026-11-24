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
            val owner = coroutineContext
            val term = query.trim()
            val basePath = base.toPath()
            Files.walkFileTree(basePath, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    owner.ensureActive()
                    return if (dir != basePath && (dir.fileName.toString() == ".git" ||
                            WorkspaceImport.isStagingName(dir.fileName.toString())))
                        java.nio.file.FileVisitResult.SKIP_SUBTREE else java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    owner.ensureActive()
                    if (attrs.isRegularFile && !WorkspaceImport.isStagingName(path.fileName.toString()) && path.fileName.toString() != ".git") {
                        val relative = basePath.relativize(path).toString()
                        if (relative.contains(term, ignoreCase = true)) {
                            val file = path.toFile().canonicalFile
                            if (file.path.startsWith(base.path + File.separator))
                                result += ComposerReference("file", file.name, file.absolutePath)
                        }
                    }
                    return if (result.size >= 101) java.nio.file.FileVisitResult.TERMINATE
                        else java.nio.file.FileVisitResult.CONTINUE
                }
            })
            ComposerFileSearchResult(result.take(100), result.size > 100)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { ComposerFileSearchResult(emptyList(), error = failure.message ?: "无法读取项目文件") }
    }
}

@Composable
internal fun ComposerFileReferenceSheet(vm: CodexViewModel, workspace: String, initialQuery: String,
                                        onSelect: (ComposerReference) -> Unit, onDismiss: () -> Unit) {
    var query by remember(workspace, initialQuery) { mutableStateOf(initialQuery) }
    val result by key(workspace, query) {
        produceState<ComposerFileSearchResult?>(null, workspace, query) {
            delay(150)
            value = ComposerFileSearch.search(File(vm.workingDirectory), File(workspace), query)
        }
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
                AtlasSettingsActionRow(file.name, File(file.path).relativeTo(File(workspace)).path,
                    CodexGlyph.Description, onClick = { onSelect(file) }, outlined = false,
                    modifier = Modifier.testTag("composer-reference-result-" + file.path))
            }
        }
    }
}

@Composable
internal fun ComposerSkillSheet(vm: CodexViewModel, workspace: String, onSelect: (ComposerReference) -> Unit, onDismiss: () -> Unit) {
    val key = vm.extensions.skillKey(workspace)
    val data by vm.data.collectEntries(key)
    val errors by vm.errors.collectEntries(key)
    val loading by vm.loading.collectProjection { it.intersect(setOf(key, "skillsWrite")) }
    val status by vm.bridge.status.collectAsState()
    fun reload() = vm.extensions.refreshSkills(workspace)
    LaunchedEffect(workspace, status) { if (status == EngineStatus.Connected) reload() }
    val skills = records(data[key]).flatMap { group ->
        group.optJSONArray("skills")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()
    }.filter { it.optBoolean("enabled") && it.optString("path").isNotBlank() && it.optString("name").isNotBlank() }
        .distinctBy { it.optString("path") }
    var query by remember(workspace) { mutableStateOf("") }
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

/** Work directories are browsed locally; changing one preserves the prior conversation. */
@Composable
internal fun ComposerWorkspaceSheet(vm: CodexViewModel, workspace: String, onRegistry: () -> Unit, onDismiss: () -> Unit) {
    val root = remember { File(vm.workingDirectory).canonicalFile }
    var directory by remember { mutableStateOf(File(workspace)) }
    var error by remember { mutableStateOf<String?>(null) }
    val result by produceState<Result<List<File>>?>(null, directory) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                val base = WorkspaceSelectionContract.validate(root, directory)
                val children = base.listFiles() ?: error("无法读取目录")
                children.filter { it.isDirectory && !it.name.startsWith(".") && !Files.isSymbolicLink(it.toPath()) }
                    .map { WorkspaceSelectionContract.validate(root, it) }
                    .distinct().sortedBy { it.name.lowercase() }
            }
        }
    }
    CodexDetailSheet(title = "选择工作区", onDismiss = onDismiss,
        subtitle = "选择目录开始新任务，原会话和草稿会保留。") {
        Text(if (directory == root) WorkspaceStorage.DISPLAY_ROOT else directory.relativeTo(root).path, style = MaterialTheme.typography.titleSmall)
        error?.let { ErrorText(it) }
        CodexPrimaryButton(onClick = {
            try { vm.selectWorkspace(directory); onDismiss() }
            catch (failure: Exception) { error = failure.message ?: "无法切换工作区" }
        }, modifier = Modifier.fillMaxWidth()) { Text("使用当前目录") }
        AtlasSettingsActionRow("已登记项目", "打开项目清单", CodexGlyph.Project, onClick = onRegistry, outlined = false)
        if (directory != root) AtlasSettingsActionRow("上一级目录", "", CodexGlyph.ArrowBack,
            onClick = { directory = directory.parentFile ?: root }, outlined = false)
        val current = result
        if (current == null) AtlasOperationProgress("正在读取目录")
        else current.fold(onSuccess = { dirs ->
            if (dirs.isEmpty()) Text("没有子目录", color = Ink.muted)
            dirs.forEach { child ->
                AtlasSettingsActionRow(child.name, "", CodexGlyph.Folder, onClick = { directory = child }, outlined = false)
            }
        }, onFailure = { ErrorText(it.message ?: "无法读取目录") })
    }
}
