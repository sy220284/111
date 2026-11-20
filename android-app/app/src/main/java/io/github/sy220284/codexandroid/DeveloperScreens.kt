package io.github.sy220284.codexandroid

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

private const val MAX_IMPORT_BYTES = 120L * 1024 * 1024
private const val MAX_IMPORT_ENTRIES = 12000

@Composable
fun WorkspacePage(vm: CodexViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { File(vm.workingDirectory).canonicalFile }
    var folder by remember { mutableStateOf(root) }
    var focus by remember { mutableStateOf<File?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var createName by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "imported-file"
                    require(!name.contains('/') && !name.contains('\\') && name != "." && name != "..") { "文件名称不合法" }
                    val destination = File(root, name).canonicalFile
                    require(destination.parentFile == root) { "非法文件路径" }
                    ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        if (name.endsWith(".zip", ignoreCase = true)) {
                            var sum = 0L
                            var count = 0
                            ZipInputStream(stream).use { zip ->
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    count++
                                    require(count <= MAX_IMPORT_ENTRIES) { "压缩包条目超过安全上限" }
                                    val out = File(root, entry.name).canonicalFile
                                    require(out.path.startsWith(root.path + File.separator)) { "压缩包包含非法路径" }
                                    if (entry.isDirectory) out.mkdirs() else {
                                        out.parentFile?.mkdirs()
                                        out.outputStream().use { sink ->
                                            val buffer = ByteArray(8192)
                                            while (true) {
                                                val size = zip.read(buffer)
                                                if (size < 0) break
                                                sum += size
                                                require(sum <= MAX_IMPORT_BYTES) { "导入内容超过 120 MB 限额" }
                                                sink.write(buffer, 0, size)
                                            }
                                        }
                                    }
                                    zip.closeEntry()
                                }
                            }
                            "已导入 " + count + " 个工程条目"
                        } else {
                            var total = 0L
                            destination.outputStream().use { sink ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val size = stream.read(buffer)
                                    if (size < 0) break
                                    total += size
                                    require(total <= MAX_IMPORT_BYTES) { "单文件超过 120 MB 限额" }
                                    sink.write(buffer, 0, size)
                                }
                            }
                            "已导入 " + name
                        }
                    } ?: throw IllegalStateException("无法打开所选文件")
                }
                notice = result
                refresh++
            } catch (failure: Exception) {
                notice = "导入失败：" + (failure.message ?: "未知错误")
                refresh++
            }
        }
    }

    if (focus != null) {
        FileEditor(focus!!, root, onBack = { focus = null; refresh++ })
        return
    }
    val children = remember(folder, refresh) { folder.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList() }
    ScreenColumn {
        SectionIntro("LOCAL / FILES", "工作区文件", "Android 私有空间中的工程。可导入文件或 ZIP 项目。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("当前目录", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            Text(folder.relativeTo(root).path.takeIf { it != "." }?.let { "projects/" + it } ?: "projects/", fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folder.parentFile?.let { if (it.path.startsWith(root.path)) folder = it } },
                    enabled = folder != root) { Icon(Icons.Outlined.ArrowBack, null); Text("上一级") }
                Button(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(6.dp)); Text("导入文件")
                }
            }
        }
        if (notice.isNotBlank()) {
            Text(notice, color = if (notice.startsWith("已")) Ink.jade else Ink.red, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(createName, { createName = it }, label = { Text("新建文本文件名") },
                modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                try {
                    require(createName.isNotBlank() && !createName.contains('/') && !createName.contains('\\'))
                    val target = File(folder, createName).canonicalFile
                    require(target.parentFile == folder.canonicalFile)
                    require(target.createNewFile()) { "文件已存在" }
                    createName = ""; refresh++
                } catch (failure: Exception) { notice = failure.message ?: "创建文件失败" }
            }) { Icon(Icons.Outlined.Add, "新建文件") }
        }
        if (children.isEmpty()) MaterialPanel(Modifier.fillMaxWidth()) { Text("目录为空，导入工程开始工作。", color = Ink.muted) }
        children.forEach { file ->
            Surface(
                color = Ink.panel, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Ink.border.copy(alpha = .55f)),
                modifier = Modifier.fillMaxWidth().clickable { if (file.isDirectory) folder = file else focus = file }
            ) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = Ink.copper)
                    Spacer(Modifier.width(11.dp))
                    Text(file.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
                }
            }
        }
    }
}

@Composable
private fun FileEditor(file: File, root: File, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var content by remember(file.path) { mutableStateOf(if (file.length() <= 1_000_000L) file.readText() else "") }
    var message by remember { mutableStateOf("") }
    val editable = file.length() <= 1_000_000L
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回文件列表") }
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("本地文件编辑", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = {
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            require(file.canonicalPath.startsWith(root.path + File.separator)) { "编辑路径超出工作区" }
                            file.writeText(content)
                        }
                        message = "已保存到工作区"
                    } catch (failure: Exception) { message = failure.message ?: "保存失败" }
                }
            }, enabled = editable) { Icon(Icons.Outlined.Save, null); Text("保存") }
        }
        if (message.isNotEmpty()) Text(message, color = Ink.jade)
        if (!editable) Text("文件超过 1 MB，请通过专用工具处理。", color = Ink.red)
        OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxSize(),
            enabled = editable, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
fun DiffPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val diff by vm.latestDiff.collectAsState()
    val active by vm.activeThread.collectAsState()
    val lines = remember(diff) { diff.lines() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionIntro("PATCH / REVIEW", "代码差异", "绿色新增 · 红色删除 · 中性上下文") }
            IconButton(onClick = { navigate("review") }) { Icon(Icons.Outlined.FactCheck, "发起代码审查") }
        }
        if (active == null || diff.isBlank()) {
            ScreenColumn {
                MaterialPanel(Modifier.fillMaxWidth()) {
                    Text("暂无可展示的代码差异", style = MaterialTheme.typography.titleMedium)
                    Text("运行修改文件的任务后，通过 turn/diff/updated 实时呈现差异。", color = Ink.muted)
                    TextButton(onClick = { navigate("chat") }) { Text("返回任务") }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(lines) { line ->
                    val tint = when {
                        line.startsWith("+") && !line.startsWith("+++") -> Ink.jade
                        line.startsWith("-") && !line.startsWith("---") -> Ink.red
                        line.startsWith("@@") -> Ink.copper
                        else -> Ink.muted
                    }
                    Text(line.ifEmpty { " " }, color = tint, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().background(Ink.recessed).padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
fun TerminalPage(vm: CodexViewModel) {
    var command by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    ScreenColumn {
        SectionIntro("LOCAL / COMMAND", "本地终端", "明确命令后由官方 app-server 执行，并回传 stdout、stderr 与退出码。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("Android 进程级隔离", style = MaterialTheme.typography.titleMedium)
            Text("工作区：" + vm.workingDirectory, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            Text("仅在点击执行后发送命令。命令可修改应用工作区，执行前请复核。", color = Ink.muted)
            OutlinedTextField(command, { command = it }, label = { Text("Shell 命令") },
                placeholder = { Text("pwd && ls -la") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
            Button(onClick = { confirm = true }, enabled = command.isNotBlank() && "terminal" !in loading) {
                Icon(Icons.Outlined.PlayArrow, null); Text("执行命令")
            }
        }
        errors["terminal"]?.let { ErrorText(it) }
        data["terminal"]?.let { result ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("返回码 " + result.optInt("exitCode"), style = MaterialTheme.typography.titleMedium)
                }
                DividerLine()
                Text(result.optString("stdout").ifBlank { "(无标准输出)" }, fontFamily = FontFamily.Monospace, color = Ink.parchment)
                val stderr = result.optString("stderr")
                if (stderr.isNotBlank()) Text(stderr, fontFamily = FontFamily.Monospace, color = Ink.red)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("确认执行命令") },
        text = { Text(command + "\n\n执行目录：" + vm.workingDirectory) },
        confirmButton = { TextButton(onClick = {
            val script = JSONArray().put("/system/bin/sh").put("-c").put(command)
            val params = JSONObject().put("command", script)
                .put("cwd", vm.workingDirectory)
                .put("sandboxPolicy", JSONObject().put("type", "externalSandbox").put("networkAccess", "restricted"))
                .put("timeoutMs", 30_000)
            vm.rpc("terminal", "command/exec", params)
            confirm = false
        }) { Text("确认执行") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
    )
}

@Composable
fun ReviewPage(vm: CodexViewModel) {
    val active by vm.activeThread.collectAsState()
    val errors by vm.errors.collectAsState()
    val response by vm.data.collectAsState()
    var selection by remember { mutableStateOf("uncommittedChanges") }
    var value by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    ScreenColumn {
        SectionIntro("CODE / QUALITY", "代码审查", "使用 Codex 原生 reviewer，对变更给出可追踪的问题。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Text("审查范围", style = MaterialTheme.typography.titleMedium)
            listOf("uncommittedChanges" to "尚未提交的改动", "baseBranch" to "相对基线分支",
                "commit" to "指定提交", "custom" to "自定义目标").forEach { (key, text) ->
                Row(Modifier.fillMaxWidth().clickable { selection = key }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selection == key, onClick = { selection = key })
                    Text(text)
                }
            }
            if (selection != "uncommittedChanges") OutlinedTextField(value, { value = it },
                label = { Text(if (selection == "baseBranch") "基线分支" else if (selection == "commit") "Commit SHA" else "审查说明") },
                modifier = Modifier.fillMaxWidth())
            Button(onClick = { confirm = true }, enabled = active != null &&
                (selection == "uncommittedChanges" || value.isNotBlank())) {
                Icon(Icons.Outlined.FactCheck, null); Spacer(Modifier.width(8.dp)); Text("发起审查")
            }
        }
        if (active == null) Text("先在任务对话中打开一个会话。", color = Ink.muted)
        errors["review"]?.let { ErrorText(it) }
        response["review"]?.let { MaterialPanel(Modifier.fillMaxWidth()) { Text(it.toString(2), fontFamily = FontFamily.Monospace) } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("执行代码审查") },
        text = { Text("将使用当前会话执行 Codex 审查，并在任务流展示结果。") },
        confirmButton = { TextButton(onClick = {
            val target = JSONObject().put("type", selection)
            when (selection) {
                "baseBranch" -> target.put("branch", value)
                "commit" -> target.put("sha", value)
                "custom" -> target.put("instructions", value)
            }
            vm.rpc("review", "review/start", JSONObject().put("threadId", active)
                .put("delivery", "inline").put("target", target))
            confirm = false
        }) { Text("开始审查") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回") } })
}
