package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun PageContent(id: String, vm: CodexViewModel, navigate: (String) -> Unit) {
    when (id) {
        "home" -> HomePage(vm, navigate)
        "chat" -> ConversationPage(vm, navigate)
        "threads" -> ThreadListPage(vm, navigate)
        "files" -> WorkspacePage(vm)
        "diff" -> DiffPage(vm, navigate)
        "terminal" -> TerminalPage(vm)
        "review" -> ReviewPage(vm)
        "approvals" -> ApprovalsPage(vm)
        "account" -> AccountPage(vm)
        "config" -> ConfigPage(vm)
        "appearance" -> AppearancePage()
        "security" -> SecurityPage(vm, navigate)
        "about" -> AboutPage(vm)
        "search" -> ThreadSearchPage(vm, navigate)
        "goals", "queue" -> ThreadScopedPage(id, vm)
        else -> CatalogPage(pages.first { it.id == id }, vm)
    }
}

@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content
    )
}

@Composable
private fun HomePage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val prompts by vm.bridge.prompts.collectAsState()
    val data by vm.data.collectAsState()
    val threads = records(data["threads"])
    ScreenColumn {
        SectionIntro("YOUR COMMAND CENTER", "让代码，流动起来", "直接在手机上管理项目、任务与 Codex 执行过程。")
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(15.dp), color = Ink.copper.copy(alpha = .17f)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Ink.copper, modifier = Modifier.padding(13.dp).size(25.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("开始新任务", style = MaterialTheme.typography.titleLarge)
                    Text("输入需求，查看执行、变更与结果", color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = { vm.newThread(); navigate("chat") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp)) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("新建 Codex 会话")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallStat("本机内核", if (status == EngineStatus.Connected) "已连接" else "待连接", Icons.Outlined.Memory, Modifier.weight(1f))
            SmallStat("待审批", prompts.size.toString(), Icons.Outlined.GppGood, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallStat("历史会话", threads.size.toString(), Icons.Outlined.History, Modifier.weight(1f))
            SmallStat("工作目录", "本地存储", Icons.Outlined.Folder, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("继续最近工作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { navigate("threads") }) { Text("全部会话") }
        }
        if (threads.isEmpty()) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("还没有可恢复的会话", color = Ink.parchment)
                Text("新建任务后，历史会话会在这里出现。", color = Ink.muted)
            }
        } else {
            threads.take(5).forEach { item ->
                ThreadRow(item) {
                    vm.chooseThread(item.optString("id"))
                    navigate("chat")
                }
            }
        }
        if (prompts.isNotEmpty()) {
            Button(onClick = { navigate("approvals") }, colors = ButtonDefaults.buttonColors(containerColor = Ink.red.copy(alpha = .18f), contentColor = Ink.parchment)) {
                Icon(Icons.Outlined.WarningAmber, null)
                Spacer(Modifier.width(8.dp))
                Text("有 " + prompts.size + " 项操作需要确认")
            }
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    MaterialPanel(modifier) {
        Icon(icon, null, tint = Ink.copper)
        Spacer(Modifier.height(16.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Text(label, color = Ink.muted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ThreadRow(item: JSONObject, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Ink.panel, border = BorderStroke(1.dp, Ink.border.copy(alpha = .5f)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forum, null, tint = Ink.copper)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.optString("name").ifBlank { item.optString("preview").ifBlank { "未命名会话" } },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(item.optString("cwd").ifBlank { item.optString("id") }, color = Ink.muted,
                    maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = Ink.muted)
        }
    }
}

@Composable
private fun ThreadListPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    var query by remember { mutableStateOf("") }
    val list = records(data["threads"]).filter {
        query.isEmpty() || (it.optString("name") + it.optString("preview") + it.optString("cwd")).contains(query, true)
    }
    LaunchedEffect(Unit) { vm.refresh("threads", "thread/list", JSONObject().put("limit", 100)) }
    ScreenColumn {
        SectionIntro("THREADS / LOCAL HISTORY", "会话历史", "随时返回工程任务，继续上下文。")
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索会话名称和路径") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true)
        Button(onClick = { vm.newThread(); navigate("chat") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("新建会话")
        }
        errors["threads"]?.let { ErrorText(it) }
        if (list.isEmpty()) {
            MaterialPanel(Modifier.fillMaxWidth()) { Text("当前列表为空", color = Ink.muted) }
        }
        list.forEach { t -> ThreadRow(t) { vm.chooseThread(t.optString("id")); navigate("chat") } }
        TextButton(onClick = { vm.refresh("threads", "thread/list", JSONObject().put("limit", 100)) }) { Text("刷新会话列表") }
    }
}

@Composable
private fun ConversationPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val status by vm.bridge.status.collectAsState()
    val thread by vm.activeThread.collectAsState()
    val busy by vm.running.collectAsState()
    val items by vm.timeline.collectAsState()
    val models by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    var draft by rememberSaveable { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(items.size) { if (items.isNotEmpty()) scroll.animateScrollToItem(items.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (thread == null) "新的工作会话" else "进行中的任务", style = MaterialTheme.typography.titleMedium)
                Text(thread?.take(18) ?: "请选择模型并输入任务", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { vm.newThread() }) { Text("新建") }
            IconButton(onClick = { navigate("diff") }) { Icon(Icons.Outlined.Difference, "查看代码差异") }
        }
        DividerLine()
        Box(Modifier.weight(1f)) {
            if (items.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Ink.copper, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("从一个具体目标开始", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Codex 将按会话、步骤与工具执行呈现过程。", style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
                }
            } else LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { item ->
                    val user = item.kind == "user"
                    Surface(
                        modifier = Modifier.fillMaxWidth(if (user) .92f else 1f),
                        color = if (user) Ink.copper.copy(alpha = .13f) else Ink.panel,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, if (user) Ink.copperDim else Ink.border.copy(alpha = .6f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (user) Icons.Outlined.Person else Icons.Outlined.Code, null, tint = if (user) Ink.copper else Ink.jade, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (user) "你" else item.kind, color = Ink.muted, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f))
                                if (item.status.isNotBlank()) Text(item.status, color = Ink.jade, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(item.text.ifBlank { "处理中…" }, style = MaterialTheme.typography.bodyLarge,
                                fontFamily = if (item.kind == "commandExecution") FontFamily.Monospace else null)
                        }
                    }
                }
            }
        }
        errors["conversation"]?.let { ErrorText(it) }
        DividerLine()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    OutlinedButton(onClick = { showModels = true }, enabled = status == EngineStatus.Connected) {
                        Text(vm.modelId.collectAsState().value.ifBlank { "默认模型" }, maxLines = 1)
                        Icon(Icons.Outlined.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = showModels, onDismissRequest = { showModels = false }) {
                        records(models["models"]).take(30).forEach { model ->
                            val id = model.optString("id").ifBlank { model.optString("model") }
                            DropdownMenuItem(text = { Text(model.optString("displayName").ifBlank { id }) },
                                onClick = { vm.modelId.value = id; showModels = false })
                        }
                        if (records(models["models"]).isEmpty()) {
                            DropdownMenuItem(text = { Text("刷新模型目录") }, onClick = {
                                vm.refresh("models", "model/list"); showModels = false
                            })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    TextButton(onClick = { vm.interrupt() }) {
                        Icon(Icons.Outlined.StopCircle, null); Text("停止任务")
                    }
                } else StatusPill(if (status == EngineStatus.Connected) "就绪" else "未连接", status == EngineStatus.Connected)
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                placeholder = { Text("描述目标、文件及验收要求…") },
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )
            Button(
                onClick = { val message = draft.trim(); draft = ""; vm.sendPrompt(message); Unit },
                enabled = draft.isNotBlank() && !busy && status == EngineStatus.Connected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.ArrowUpward, null); Spacer(Modifier.width(8.dp)); Text("发送给 Codex")
            }
        }
    }
}

/** Different upstream responses use data, threads, or a module-specific array. */
fun records(response: JSONObject?): List<JSONObject> {
    if (response == null) return emptyList()
    val keys = listOf("data", "threads", "skills", "models", "plugins", "apps", "items",
        "hooks", "servers", "environments", "providers", "modes", "results", "accounts")
    for (key in keys) {
        val array = response.optJSONArray(key)
        if (array != null) return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    return emptyList()
}

@Composable
fun ErrorText(error: String) {
    Surface(color = Ink.red.copy(alpha = .12f), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.red.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = Ink.red)
            Spacer(Modifier.width(8.dp))
            Text(error, color = Ink.red, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun CatalogPage(page: Page, vm: CodexViewModel) {
    val result by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val payload = result[page.id]
    val entries = records(payload)
    var expanded by remember { mutableStateOf(false) }
    var operation by remember(page.id) { mutableStateOf(page.rpc ?: "") }
    var arguments by remember(page.id) { mutableStateOf("{}") }
    LaunchedEffect(page.id) { page.rpc?.let { vm.refresh(page.id, it) } }
    ScreenColumn {
        SectionIntro("CODEX / " + page.section, page.title, page.subtitle)
        if (page.experimental) StatusPill("上游实验接口", false)
        MaterialPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(page.icon, null, tint = Ink.copper)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("实时服务数据", style = MaterialTheme.typography.titleMedium)
                    Text(page.rpc ?: "本地功能", color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { page.rpc?.let { vm.refresh(page.id, it) } }) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
            }
            if (page.id in loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            errors[page.id]?.let { ErrorText(it) }
        }
        if (entries.isNotEmpty()) entries.forEachIndexed { idx, entry ->
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.optString("displayName")
                            .ifBlank { entry.optString("name") }
                            .ifBlank { entry.optString("title") }
                            .ifBlank { entry.optString("id") }
                            .ifBlank { "记录 " + (idx + 1) },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                    )
                    entry.optString("status").takeIf { it.isNotBlank() }?.let { StatusPill(it) }
                }
                Text(entry.optString("description").ifBlank { entry.optString("summary") },
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
                var showDetail by remember { mutableStateOf(false) }
                TextButton(onClick = { showDetail = !showDetail }) { Text(if (showDetail) "收起详情" else "查看详情") }
                AnimatedVisibility(showDetail) {
                    Text(entry.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        } else if (payload != null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("服务返回", style = MaterialTheme.typography.titleMedium)
                Text(payload.toString(2), fontFamily = FontFamily.Monospace, color = Ink.muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else if (page.id !in loading && page.id !in errors) {
            MaterialPanel(Modifier.fillMaxWidth()) { Text("从运行核心读取数据后将在此显示。", color = Ink.muted) }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Icon(Icons.Outlined.Terminal, null); Spacer(Modifier.width(7.dp)); Text("高级协议操作")
        }
        AnimatedVisibility(expanded) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("调用官方 App Server 接口", style = MaterialTheme.typography.titleMedium)
                Text("请求参数采用 JSON，异常会显示在本页。修改或删除操作需明确输入对应方法。", color = Ink.muted)
                OutlinedTextField(operation, { operation = it }, label = { Text("method") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(arguments, { arguments = it }, label = { Text("params JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = {
                    try { vm.rpc(page.id, operation.trim(), JSONObject(arguments)) }
                    catch (failure: Exception) { vm.errors.value = vm.errors.value + (page.id to (failure.message ?: "JSON 格式不正确")) }
                }, enabled = operation.isNotBlank()) { Text("发送请求") }
            }
        }
    }
}

@Composable
fun ThreadScopedPage(id: String, vm: CodexViewModel) {
    val active by vm.activeThread.collectAsState()
    val page = pages.first { it.id == id }
    val errors by vm.errors.collectAsState()
    val payload by vm.data.collectAsState()
    val method = page.rpc ?: return
    ScreenColumn {
        SectionIntro("THREAD / CONTEXT", page.title, page.subtitle)
        if (active == null) {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Text("需要先打开一个会话", style = MaterialTheme.typography.titleMedium)
                Text("从会话历史或任务对话中选择当前任务。", color = Ink.muted)
            }
        } else {
            Button(onClick = { vm.refresh(id, method, JSONObject().put("threadId", active)) }) {
                Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("读取当前会话")
            }
            errors[id]?.let { ErrorText(it) }
            payload[id]?.let { MaterialPanel(Modifier.fillMaxWidth()) { Text(it.toString(2), fontFamily = FontFamily.Monospace) } }
        }
    }
}

@Composable
private fun ThreadSearchPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    ScreenColumn {
        SectionIntro("SEARCH / THREADS", "全文检索", "通过 Codex 的历史检索定位旧任务。")
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("搜索词") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Button(onClick = { vm.refresh("search", "thread/search", JSONObject().put("searchTerm", query)) }, enabled = query.isNotBlank()) { Text("搜索") }
        errors["search"]?.let { ErrorText(it) }
        records(data["search"]).forEach { entry ->
            ThreadRow(entry) {
                vm.chooseThread(entry.optString("id"))
                navigate("chat")
            }
        }
    }
}
