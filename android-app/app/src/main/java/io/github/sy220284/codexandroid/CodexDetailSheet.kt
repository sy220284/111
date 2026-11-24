package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 说明与较长操作使用独立底部面板，列表本身保持稳定，不随“查看更多”改变高度。
 * 默认允许半屏与全屏两级停靠；状态来自调用方，关闭后不会清除未提交的编辑内容。
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = startExpanded),
        containerColor = Ink.elevated,
        contentColor = Ink.parchment,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.muted) },
        modifier = Modifier.testTag("codex-detail-sheet")
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = atlasSheetHeight()).imePadding().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
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
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("codex-detail-close")
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = closeDescription)
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
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(label)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null,
            modifier = Modifier.size(18.dp))
    }
}

/** Action Sheet 对象，业务回调仍由实际页面持有。 */
data class CodexSheetAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val description: String = "",
    val destructive: Boolean = false,
    val enabled: Boolean = true
)

/** 用于会话或文件的上下文操作；选择后先关闭菜单，再交由原页面执行或确认。 */
@Composable
fun CodexActionSheet(
    title: String,
    actions: List<CodexSheetAction>,
    onDismiss: () -> Unit
) {
    CodexDetailSheet(title = title, onDismiss = onDismiss) {
        CodexActionRows(actions, onDismiss)
    }
}

/**
 * 页面内已经展示底部面板时，操作使用轻量对话框。
 * 保留同一套按钮、禁用态和危险操作语义，避免两层底部面板相互遮挡。
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
    onDismiss: () -> Unit
) {
    actions.forEach { action ->
            val tone = if (action.destructive) Ink.red else Ink.parchment
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(enabled = action.enabled, role = Role.Button) {
                        onDismiss()
                        action.onClick()
                    },
                color = Ink.panel,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Ink.border)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(action.icon, contentDescription = null,
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
    val contentLimit = (LocalConfiguration.current.screenHeightDp.dp * .65f)
        .coerceAtLeast(200.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = contentLimit)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        },
        confirmButton = {
            TextButton(
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
    val contentLimit = (LocalConfiguration.current.screenHeightDp.dp * .62f)
        .coerceAtLeast(200.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding().testTag("codex-edit-dialog"),
        containerColor = Ink.elevated,
        titleContentColor = Ink.parchment,
        textContentColor = Ink.parchment,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = contentLimit)
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
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("取消")
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("codex-edit-confirm")
            ) { Text(confirmLabel) }
        }
    )
}
