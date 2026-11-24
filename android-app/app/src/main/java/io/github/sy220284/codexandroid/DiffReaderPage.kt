package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * File-first code review. The surrounding LazyColumn virtualizes every code
 * line instead of laying out the entire diff in one scrolling Column.
 */
@Composable
fun DiffReaderPage(vm: CodexViewModel, navigate: (String) -> Unit) {
    val diff by vm.latestDiff.collectAsState()
    val active by vm.activeThread.collectAsState()
    val files = remember(diff) { UnifiedDiff.parse(diff) }
    val context = LocalContext.current
    var expanded by remember(active, diff) {
        mutableStateOf(DiffExpansionPreferences.load(context, active, diff, files.size))
    }
    fun updateExpanded(next: Set<Int>) {
        DiffExpansionPreferences.save(context, active, diff, next)
        expanded = next
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember(diff) { mutableStateOf(false) }
    var wrapLines by remember { mutableStateOf(true) }
    var query by remember(diff) { mutableStateOf("") }
    val visibleFiles = files.indices.filter { files[it].path.contains(query.trim(), ignoreCase = true) }
    val lineNumbers = remember(files) { files.map { UnifiedDiff.annotateLines(it.lines) } }
    val listState = rememberLazyListState()
    var filePicker by remember { mutableStateOf(false) }
    var pendingJump by remember(diff) { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingJump, expanded, visibleFiles) {
        val target = pendingJump ?: return@LaunchedEffect
        if (target in visibleFiles) {
            val position = 1 + visibleFiles.takeWhile { it != target }.sumOf { index ->
                1 + if (index in expanded) files[index].lines.size else 0
            }
            listState.scrollToItem(position)
        }
        pendingJump = null
    }
    if (filePicker) CodexChoiceSheet(title = "定位改动文件", description = "选择文件后跳转至差异位置",
        choices = visibleFiles.map { index -> CodexChoice(index.toString(), files[index].path) },
        selectedId = null, onSelect = { value ->
            val target = value.toInt()
            updateExpanded(expanded + target)
            pendingJump = target
            filePicker = false
        }, onDismiss = { filePicker = false }, searchable = true)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CodexIcon(CodexGlyph.Diff, contentDescription = null,
                        tint = Ink.copper, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("本次改动", style = MaterialTheme.typography.titleMedium)
                        Text(if (files.isEmpty()) "尚无可读差异"
                            else "${files.size} 个文件 · +${files.sumOf { it.additions }} / −${files.sumOf { it.deletions }}",
                            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (visibleFiles.isNotEmpty()) {
                        CodexTextAction(onClick = {
                            updateExpanded(if (visibleFiles.all { it in expanded }) expanded - visibleFiles.toSet()
                            else expanded + visibleFiles.toSet())
                        }) {
                            Text(if (visibleFiles.all { it in expanded }) "全部收起" else "全部展开",
                                maxLines = 1)
                        }
                    }
                }
                if (files.isNotEmpty()) AtlasSearchField(query, { query = it }, "按文件名筛选差异",
                    onClear = { query = "" }, modifier = Modifier.testTag("diff-file-search"))
                if (visibleFiles.isNotEmpty()) CodexTextAction(onClick = { filePicker = true },
                    modifier = Modifier.testTag("diff-file-jump")) { Text("定位改动文件") }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    CodexPrimaryButton(onClick = { navigate("review") },
                        enabled = active != null && files.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        CodexIcon(CodexGlyph.FactCheck, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("代码审查")
                    }
                    CodexSecondaryButton(onClick = {
                        clipboard.setText(AnnotatedString(diff))
                        copied = true
                    }, enabled = diff.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        CodexIcon(CodexGlyph.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (copied) "已复制" else "复制全部差异")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CodexCheckBox(checked = wrapLines, onCheckedChange = { wrapLines = it },
                        modifier = Modifier.testTag("diff-line-wrap"))
                    Text("长代码行自动换行", style = MaterialTheme.typography.labelMedium,
                        color = Ink.muted)
                }
            }
        }
        if (active == null || diff.isBlank()) {
            item {
                CodexEmptyState(
                    "暂无可展示的代码差异",
                    if (active == null) "先启动或恢复任务，修改文件后会显示实时差异。"
                    else "当前会话尚未上报代码修改。可以返回任务继续工作。",
                    actionLabel = "返回任务",
                    onAction = { navigate("chat") },
                    icon = CodexGlyph.Code,
                    artworkRes = R.drawable.ic_empty_diff
                )
            }
        } else {
            if (visibleFiles.isEmpty()) item {
                CodexEmptyState("没有匹配的文件", "更换文件名或清除筛选。",
                    actionLabel = "清除筛选", onAction = { query = "" })
            }
            visibleFiles.forEach { index ->
                val file = files[index]
                item(key = "diff-heading-$index") {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CodexIcon(if (file.binary) CodexGlyph.Attachment
                                else CodexGlyph.Description,
                                contentDescription = null, tint = Ink.copper,
                                modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.path, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (file.binary) "二进制文件变更"
                                    else "+${file.additions} 行 · −${file.deletions} 行",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Ink.muted)
                            }
                            CodexTextAction(
                                onClick = {
                                    updateExpanded(if (index in expanded) expanded - index
                                    else expanded + index)
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(if (index in expanded) "收起" else "展开")
                                CodexIcon(if (index in expanded) CodexGlyph.ExpandLess
                                    else CodexGlyph.ExpandMore,
                                    contentDescription = null)
                            }
                        }
                    }
                }
                if (index in expanded) {
                    items(file.lines.size, key = { line -> "diff-line-$index-$line" }) { lineIndex ->
                        val line = file.lines[lineIndex]
                        val tone = when {
                            line.startsWith("+") && !line.startsWith("+++") -> Ink.jade
                            line.startsWith("-") && !line.startsWith("---") -> Ink.red
                            line.startsWith("@@") -> Ink.copper
                            else -> Ink.muted
                        }
                        Surface(
                            color = Ink.recessed,
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(.5.dp, Ink.border.copy(alpha = .32f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                val numbers = lineNumbers[index][lineIndex]
                                Text(
                                    listOf(numbers.old?.toString().orEmpty(),
                                        numbers.new?.toString().orEmpty())
                                        .joinToString(" ", transform = { it.padStart(4) }),
                                    modifier = Modifier.padding(start = 4.dp, top = 3.dp)
                                        .width(76.dp).testTag("diff-line-numbers-$index-$lineIndex"),
                                    color = Ink.muted,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelSmall,
                                    softWrap = false
                                )
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(line.ifEmpty { " " },
                                        modifier = Modifier
                                            .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                                            .padding(horizontal = 5.dp, vertical = 3.dp),
                                        color = tone,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium,
                                        softWrap = wrapLines)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
