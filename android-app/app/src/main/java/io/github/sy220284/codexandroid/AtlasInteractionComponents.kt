package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 111 交互图谱：由十张参考图片提炼的可复用原生组件。
 * 组件只处理呈现和可访问操作，搜索、提交、审批、进度均由调用方的真实状态驱动。
 */

/** 统一查询框：文本搜索使用搜索键提交；即时过滤可不提供 onSubmit。 */
@Composable
fun AtlasSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    label: String? = null,
    submitTestTag: String? = null
) {
    CodexInputField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { labelText -> { Text(labelText) } },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        leadingIcon = { CodexIcon(CodexGlyph.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotBlank() && onClear != null) {
                    CodexIconAction(
                        onClick = onClear,
                        modifier = Modifier.size(48.dp),
                        enabled = enabled
                    ) { CodexIcon(CodexGlyph.Close, contentDescription = "清除搜索词") }
                }
                if (onSubmit != null) {
                    CodexIconAction(
                        onClick = onSubmit,
                        enabled = enabled && !busy && value.isNotBlank(),
                        modifier = Modifier.size(48.dp).let {
                            if (submitTestTag == null) it else it.testTag(submitTestTag)
                        }
                    ) {
                        if (busy) CodexActivityIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Ink.copper, strokeWidth = 2.dp
                        ) else CodexIcon(CodexGlyph.ArrowForward, contentDescription = "开始搜索")
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            imeAction = if (onSubmit == null) ImeAction.Done else ImeAction.Search
        ),
        keyboardActions = KeyboardActions(
            onSearch = { if (onSubmit != null && enabled && !busy && value.isNotBlank()) onSubmit() }
        )
    )
}

/** 明确选择的是任务执行状态，而非普通内容筛选条件。 */
data class AtlasStatusOption(
    val key: String,
    val title: String,
    val tone: StatusTone
)

@Composable
fun AtlasStatusSelector(
    selected: String,
    options: List<AtlasStatusOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val toneFor: (StatusTone) -> androidx.compose.ui.graphics.Color = { tone ->
        when (tone) {
            StatusTone.Success -> Ink.jade
            StatusTone.Warning -> Ink.warning
            StatusTone.Danger -> Ink.red
            StatusTone.Info -> Ink.copper
            StatusTone.Neutral -> Ink.muted
        }
    }
    Column(
        modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.chunked(2).forEach { optionsInRow ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                optionsInRow.forEach { option ->
                    val chosen = selected == option.key
                    Surface(
                        modifier = Modifier.weight(1f)
                            .selectable(
                                selected = chosen, enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.key) }
                            ),
                        color = if (chosen) Ink.panel else Ink.canvas,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            if (chosen) 2.dp else 1.dp,
                            if (chosen) toneFor(option.tone) else Ink.border
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            CodexRadio(
                                selected = chosen, onClick = null,
                                enabled = enabled, modifier = Modifier.size(40.dp),
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = toneFor(option.tone)
                                )
                            )
                            Text(
                                option.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (optionsInRow.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 排队任务的真实顺序徽标；它只显示顺序，不伪装为完成进度。 */
@Composable
fun AtlasSequenceBadge(
    position: Int,
    total: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(34.dp).semantics { contentDescription = "第$position 项，共 $total 项" },
        shape = CircleShape,
        color = if (position == 1) Ink.jade else Ink.canvas,
        border = BorderStroke(1.dp, if (position == 1) Ink.jade else Ink.border)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(position.toString(), style = MaterialTheme.typography.labelLarge,
                color = if (position == 1) androidx.compose.ui.graphics.Color.White else Ink.parchment)
        }
    }
}

/** 已知进度才显示比例；未知进度明确标记正在处理。 */
@Composable
fun AtlasOperationProgress(
    label: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    indicatorColor: androidx.compose.ui.graphics.Color = Ink.copper
) {
    val fraction = progress?.coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().testTag("atlas-operation-progress"),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall,
                color = Ink.muted, modifier = Modifier.weight(1f))
            if (fraction != null) Text("${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium, color = Ink.parchment)
        }
        val reduced by UiPreferences.reducedMotion.collectAsState()
        if (fraction == null && reduced) {
            Box(Modifier.fillMaxWidth().height(4.dp)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .background(Ink.border, RoundedCornerShape(4.dp)))
        } else if (fraction == null) LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(), color = indicatorColor,
            trackColor = Ink.border
        ) else LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(), color = indicatorColor,
            trackColor = Ink.border
        )
    }
}

/** 说明和校验反馈紧邻字段，减少弹出额外通知造成的打断。 */
@Composable
fun AtlasFieldFeedback(
    message: String,
    isError: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth().heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        CodexIcon(if (isError) CodexGlyph.TaskError else CodexGlyph.Info,
            contentDescription = null,
            tint = if (isError) Ink.red else Ink.muted,
            modifier = Modifier.size(17.dp))
        Text(message, color = if (isError) Ink.red else Ink.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
    }
}

/** 列表总数放在标题同行，右侧操作由页面按真实可用状态提供。 */
@Composable
fun AtlasListHeading(
    title: String,
    total: Int,
    modifier: Modifier = Modifier,
    suffix: String = "项",
    action: (@Composable () -> Unit)? = null
) {
    Row(modifier.fillMaxWidth().heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("$total $suffix", color = Ink.muted,
                style = MaterialTheme.typography.labelMedium)
        }
        action?.invoke()
    }
}

/** 以真实加载状态占位，避免将上一次查询结果展示为当前结果。 */
@Composable
fun AtlasLoadingResults(
    title: String,
    modifier: Modifier = Modifier,
    placeholderRows: Int = 3
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("atlas-loading-results"),
        shape = RoundedCornerShape(18.dp),
        color = Ink.panel,
        border = BorderStroke(1.dp, Ink.border)
    ) {
        Column(Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            CodexSkeletonRows(placeholderRows)
        }
    }
}

/** 结构化选项行用于设置与功能入口，整个行高可触达而非只有右侧图标可点。 */
@Composable
fun AtlasSettingsActionRow(
    title: String,
    detail: String,
    icon: CodexGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth().codexPressable(
            enabled = enabled, role = Role.Button, onClick = onClick
        ),
        color = if (outlined) Ink.canvas else androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = if (outlined) BorderStroke(1.dp, Ink.border) else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CodexIcon(icon, contentDescription = null,
                modifier = Modifier.size(22.dp), tint = Ink.muted)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Ink.parchment else Ink.muted)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = Ink.muted)
            }
            CodexIcon(CodexGlyph.ChevronRight, contentDescription = null,
                tint = Ink.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** 排他业务选择：整行可点、长标题可换行，选中语义统一。 */
@Composable
fun AtlasChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .codexSelectable(selected, enabled, onClick),
        color = if (selected) Ink.panel else Ink.canvas,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Ink.copper else if (enabled) Ink.controlBorder else Ink.border),
        shape = RoundedCornerShape(15.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CodexRadio(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Ink.parchment else Ink.muted)
                if (description.isNotBlank()) Text(description,
                    color = Ink.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 紧凑排他操作保留一行，适合键盘展开时的输入栏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasCompactChoice(
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tagPrefix: String = "",
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (id, label) ->
            val interaction = remember(id) { MutableInteractionSource() }
            SegmentedButton(selected = selected == id, onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                enabled = enabled,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Ink.copper.copy(alpha = .12f), activeContentColor = Ink.parchment,
                    activeBorderColor = Ink.copper, inactiveContainerColor = Ink.canvas,
                    inactiveContentColor = Ink.muted, inactiveBorderColor = Ink.controlBorder),
                interactionSource = interaction,
                modifier = Modifier.heightIn(min = 48.dp).codexActionPress(interaction, enabled).let {
                    if (tagPrefix.isEmpty()) it else it.testTag(tagPrefix + id)
                }) { Text(label, style = labelStyle) }
        }
    }
}

/** 独立列表滚动面板统一按当前窗口高度约束。 */
@Composable
fun atlasSheetHeight() = LocalConfiguration.current.screenHeightDp.dp * .88f

@Composable
fun AtlasSheetHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        CodexIconAction(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("atlas-sheet-close")) {
            CodexIcon(CodexGlyph.Close, contentDescription = "关闭面板")
        }
    }
}

/** 审批操作按真实可用宽度和字号排列；主操作独占一行，拒绝及辅助操作不挤压。 */
@Composable
internal fun AtlasApprovalActions(
    primary: (@Composable (Modifier) -> Unit)?,
    secondary: (@Composable (Modifier) -> Unit)? = null,
    tertiary: (@Composable (Modifier) -> Unit)? = null
) {
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("approval-decision-actions")) {
        val sideBySide = secondary != null && tertiary != null && maxWidth >= (264 * fontScale).dp
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sideBySide) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    secondary?.invoke(Modifier.weight(1f))
                    tertiary?.invoke(Modifier.weight(1f))
                }
            } else {
                secondary?.invoke(Modifier.fillMaxWidth())
                tertiary?.invoke(Modifier.fillMaxWidth())
            }
            primary?.invoke(Modifier.fillMaxWidth())
        }
    }
}
