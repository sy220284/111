package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class AndroidThreadSection(val id: String, val name: String)

@Composable
internal fun ThreadSectionSheet(
    vm: CodexViewModel,
    activeSection: AndroidThreadSection?,
    moving: Map<String, Set<String>>?,
    onFilter: (AndroidThreadSection?) -> Unit,
    onMoved: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val operation = rememberCapabilityOperation(vm)
    var sections by remember { mutableStateOf<List<AndroidThreadSection>?>(null) }
    var editing by remember { mutableStateOf<AndroidThreadSection?>(null) }
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<AndroidThreadSection?>(null) }
    var remaining by remember(moving) { mutableStateOf(moving.orEmpty()) }
    var attempt by remember(moving) { mutableStateOf<SectionMoveAttempt?>(null) }
    var abandon by remember(moving) { mutableStateOf(false) }
    suspend fun load() {
        sections = capabilityPages { cursor ->
            vm.capabilityRequest("threadSection/list", capabilityPageParams(cursor))
        }.map {
            val id = it.getString("id")
            AndroidThreadSection(id, if (id == ThreadSectionContract.PINNED) "已置顶（同步分组）" else it.getString("name"))
        }
    }
    fun move(target: String?, label: String) {
        if (operation.busy || remaining.isEmpty()) return
        val destination = attempt ?: SectionMoveAttempt(target, label).also { attempt = it }
        check(destination.accepts(target)) { "请先完成当前分组的移动" }
        operation.launch {
            val completed = mutableSetOf<String>()
            val failed = linkedMapOf<String, Set<String>>()
            var lastError = ""
            for ((row, versions) in remaining) {
                val pending = mutableSetOf<String>()
                for (thread in versions) {
                    try {
                        check(thread !in vm.threadExecutions.value && !vm.threadSwitching.value &&
                            !vm.firstThreadStarting.value) { "此会话正在运行或切换中" }
                        vm.capabilityRequest("thread/section/move", ThreadSectionContract.move(thread, target))
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
                        pending += thread
                        lastError = capabilityError(cancel)
                    } catch (failure: Exception) {
                        pending += thread
                        lastError = capabilityError(failure)
                    }
                }
                if (pending.isEmpty()) completed += row else failed[row] = pending
            }
            onMoved(completed)
            remaining = failed
            if (failed.isEmpty()) onDismiss()
            else operation.error = "已移动 ${completed.size} 条，${failed.size} 条待重试。$lastError"
        }
    }
    LaunchedEffect(vm) { operation.launch { load() } }
    CodexDetailSheet(if (moving == null) "会话分组" else "移动 ${remaining.size} 条会话",
        onDismiss = {
            if (!operation.busy) {
                if (attempt != null && remaining.isNotEmpty()) abandon = true else onDismiss()
            }
        }) {
        CapabilityFeedback(operation, if (moving == null) "正在同步分组" else "正在处理会话分组")
        attempt?.let { destination ->
            CodexNotice("目标：${destination.label}。已成功的版本保持原结果，剩余版本仅重试此目标。", NoticeTone.Warning)
            CodexPrimaryButton(onClick = { move(destination.target, destination.label) },
                enabled = !operation.busy && remaining.isNotEmpty()) { Text("重试未完成移动") }
        }
        if (sections == null && !operation.busy && attempt == null) {
            CodexSecondaryButton(onClick = { operation.launch { load() } }) { Text("重新读取分组") }
        }
        if (moving == null) {
            CodexSecondaryButton(onClick = { onFilter(null) }, enabled = !operation.busy) { Text("全部会话") }
        }
        CodexSecondaryButton(onClick = {
            if (moving == null) onFilter(AndroidThreadSection(ThreadSectionContract.UNGROUPED, "未分组"))
            else move(null, "未分组")
        }, enabled = !operation.busy && attempt == null && (moving == null || remaining.isNotEmpty())) {
            Text(if (moving == null) "未分组" else "移出分组")
        }
        AtlasSearchField(query, { query = it }, "搜索分组", onClear = { query = "" })
        sections.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }.forEach { section ->
            Row(Modifier.fillMaxWidth()) {
                CodexSecondaryButton(onClick = {
                    if (moving == null) onFilter(section) else move(section.id, section.name)
                }, enabled = !operation.busy && attempt == null, modifier = Modifier.weight(1f)) { Text(section.name) }
                if (moving == null && section.id != ThreadSectionContract.PINNED) {
                    CodexIconAction(onClick = { editing = section; name = section.name }, enabled = !operation.busy) {
                        CodexIcon(CodexGlyph.Edit, "重命名 ${section.name}")
                    }
                    CodexIconAction(onClick = { deleting = section }, enabled = !operation.busy) {
                        CodexIcon(CodexGlyph.DeleteOutline, "删除分组 ${section.name}")
                    }
                }
            }
        }
        CodexTextAction(onClick = { editing = AndroidThreadSection("", ""); name = "" },
            enabled = !operation.busy && attempt == null) { Text("新建分组") }

    }
    if (abandon) CodexConfirmDialog("结束本次移动", "仍有会话版本未移动完成。已成功的版本会保留；以后重新选中该会话，可将所有版本一起移动到同一分组。",
        confirmLabel = "结束本次移动", onConfirm = { abandon = false; onDismiss() },
        onDismiss = { abandon = false })
    editing?.let { section ->
        CodexEditDialog(if (section.id.isEmpty()) "新建分组" else "重命名分组",
            "为会话分组设置清晰名称。", "保存分组",
            onDismiss = { if (!operation.busy) editing = null },
            onConfirm = {
                operation.launch {
                    if (section.id.isEmpty()) {
                        vm.capabilityRequest("threadSection/create", ThreadSectionContract.create(name))
                    } else {
                        vm.capabilityRequest("threadSection/update", ThreadSectionContract.rename(section.id, name))
                    }
                    editing = null
                    load()
                }
            }, canConfirm = !operation.busy && name.trim().length in 1..120) {
            CodexInputField(name, { name = it }, label = { Text("分组名称") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
    }
    deleting?.let { section ->
        CodexConfirmDialog("删除分组", "分组「${section.name}」的会话会移到未分组。",
            confirmLabel = "删除分组", destructive = true,
            onConfirm = {
                deleting = null
                operation.launch {
                    vm.capabilityRequest("threadSection/delete", ThreadSectionContract.delete(section.id))
                    if (activeSection?.id == section.id) onFilter(null) else load()
                }
            }, onDismiss = { deleting = null })
    }
}
