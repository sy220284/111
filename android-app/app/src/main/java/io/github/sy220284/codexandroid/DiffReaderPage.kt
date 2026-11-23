package io.github.sy220284.codexandroid

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        item {
            MaterialPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Difference, contentDescription = null,
                        tint = Ink.copper, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("本次改动", style = MaterialTheme.typography.titleMedium)
                        Text(if (files.isEmpty()) "尚无可读差异"
                            else "${files.size} 个文件 · +${files.sumOf { it.additions }} / −${files.sumOf { it.deletions }}",
                            color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (files.isNotEmpty()) {
                        TextButton(onClick = {
                            updateExpanded(if (expanded.size == files.size) emptySet()
                            else files.indices.toSet())
                        }) {
                            Text(if (expanded.size == files.size) "全部收起" else "全部展开",
                                maxLines = 1)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = { navigate("review") },
                        enabled = active != null && files.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.FactCheck, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("代码审查")
                    }
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(diff))
                        copied = true
                    }, enabled = diff.isNotBlank(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (copied) "已复制" else "复制全部差异")
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wrapLines, onCheckedChange = { wrapLines = it },
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
                    icon = Icons.Outlined.Code,
                    artworkRes = R.drawable.ic_empty_diff
                )
            }
        } else {
            files.forEachIndexed { index, file ->
                item(key = "diff-heading-$index") {
                    MaterialPanel(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (file.binary) Icons.Outlined.Attachment
                                else Icons.Outlined.Description,
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
                            TextButton(
                                onClick = {
                                    updateExpanded(if (index in expanded) expanded - index
                                    else expanded + index)
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(if (index in expanded) "收起" else "展开")
                                Icon(if (index in expanded) Icons.Outlined.ExpandLess
                                    else Icons.Outlined.ExpandMore,
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
                            SelectionContainer {
                                Text(line.ifEmpty { " " },
                                    modifier = Modifier.fillMaxWidth()
                                        .then(if (wrapLines) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                                        .padding(horizontal = 9.dp, vertical = 3.dp),
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
