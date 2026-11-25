package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 短内容使用居中窗口，复杂任务使用全屏详情。关闭后保留调用方持有的草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String = "",
    footer: (@Composable () -> Unit)? = null,
    startExpanded: Boolean = false,
    closeDescription: String = "关闭详情",
    content: @Composable ColumnScope.() -> Unit
) {
    CodexOverlayFrame(
        onDismissRequest = onDismiss,
        fullScreen = startExpanded,
        containerColor = Ink.elevated,
        contentColor = Ink.parchment,
        modifier = Modifier.testTag("codex-detail-sheet")
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (startExpanded) Modifier.fillMaxHeight() else Modifier.heightIn(max = atlasSheetHeight()))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.testTag("codex-detail-title"))
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, color = Ink.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                CodexIconAction(
                    onClick = LocalCodexSheetClose.current ?: onDismiss,
                    modifier = Modifier.size(48.dp).testTag("codex-detail-close")
                ) {
                    CodexIcon(CodexGlyph.Close, contentDescription = closeDescription)
                }
            }
            HorizontalDivider(color = Ink.border)
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
            if (footer != null) {
                HorizontalDivider(color = Ink.border)
                footer()
            }
        }
    }
}

/** 统一的“查看详情”入口：点击后打开独立面板，列表布局不发生位移。 */
@Composable
fun CodexDetailTrigger(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CodexTextAction(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label)
        Spacer(Modifier.width(4.dp))
        CodexIcon(CodexGlyph.ChevronRight, contentDescription = null,
            modifier = Modifier.size(18.dp))
    }
}

/** Action Sheet 对象，业务回调仍由实际页面持有。 */
data class CodexSheetAction(
    val label: String,
    val icon: CodexGlyph,
    val onClick: () -> Unit,
    val description: String = "",
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val testTag: String? = null
)

/** 非锚定上下文操作统一使用居中菜单；有触发锚点时使用 CodexFloatingActionMenu。 */
@Composable
fun CodexActionSheet(
    title: String,
    actions: List<CodexSheetAction>,
    onDismiss: () -> Unit
) {
    CodexActionDialog(title, actions, onDismiss)
}

/**
 * 非锚定操作使用轻量对话框。
 * 保留同一套按钮、禁用态和危险操作语义，避免重复遮挡。
 */
@Composable
fun CodexActionDialog(
    title: String,
    actions: List<CodexSheetAction>,
    onDismiss: () -> Unit
) {
    CodexInfoDialog(title = title, onDismiss = onDismiss) {
        CodexActionRows(actions, onDismiss)
    }
}

@Composable
private fun ColumnScope.CodexActionRows(
    actions: List<CodexSheetAction>,
    onDismiss: () -> Unit,
    closeWithAction: ((() -> Unit) -> Unit)? = null
) {
    actions.forEach { action ->
            val tone = if (action.destructive) Ink.red else Ink.parchment
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .let { if (action.testTag != null) it.testTag(action.testTag) else it }
                    .codexPressable(enabled = action.enabled, role = Role.Button) {
                        if (closeWithAction != null) closeWithAction(action.onClick)
                        else { onDismiss(); action.onClick() }
                    },
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CodexIcon(action.icon, contentDescription = null,
                        tint = if (action.enabled) tone else Ink.muted,
                        modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(action.label, color = if (action.enabled) tone else Ink.muted,
                            style = MaterialTheme.typography.titleSmall)
                        if (action.description.isNotBlank()) {
                            Text(action.description, color = Ink.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
    }
}

/** 加载的占位行只表示等待数据，不伪造内容或进度。 */
@Composable
fun CodexSkeletonRows(count: Int = 3) {
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count.coerceIn(1, 6)) { index ->
            Column(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Spacer(Modifier.fillMaxWidth(if (index % 2 == 0) .62f else .45f)
                    .height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(Ink.border.copy(alpha = .52f)))
                Spacer(Modifier.fillMaxWidth(if (index % 2 == 0) .91f else .78f)
                    .height(12.dp).clip(RoundedCornerShape(6.dp))
                    .background(Ink.border.copy(alpha = .28f)))
            }
        }
    }
}

/** 当调用位置已在底部面板中，使用轻量对话框查看少量信息，避免弹层套弹层。 */
@Composable
fun CodexInfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    CodexDialogFrame(
        onDismissRequest = onDismiss,
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = codexSheetContentLimit(.65f))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = {
            CodexTextAction(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("codex-info-close")
            ) { Text("关闭") }
        }
    )
}

/**
 * 事务式表单对话框：取消不改动草稿，确认后由调用方写回真实状态。
 * 在任务目标等已有底部面板的页面中使用，可避免嵌套底部弹窗。
 */
@Composable
fun CodexEditDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    canConfirm: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    CodexDialogFrame(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding().testTag("codex-edit-dialog"),
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = codexSheetContentLimit(.62f))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (description.isNotBlank()) {
                    Text(description, color = Ink.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                content()
            }
        },
        dismissButton = {
            CodexTextAction(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
        },
        confirmButton = {
            CodexPrimaryButton(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("codex-edit-confirm")
            ) { Text(confirmLabel) }
        }
    )
}
