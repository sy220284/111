package io.github.sy220284.codexandroid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared press response; native ripple, click semantics and cancellation remain on the control. */
@Composable
internal fun Modifier.codexActionPress(interaction: MutableInteractionSource, enabled: Boolean,
    shape: Shape = RoundedCornerShape(13.dp)): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed && !reduced) .985f else 1f,
        animationSpec = tween(CodexMotion.duration(if (pressed) CodexMotion.press else CodexMotion.release, reduced)),
        label = "control-press"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (enabled && (focused || hovered)) Modifier.border(
            if (focused) 2.dp else 1.dp, if (focused) Ink.copper else Ink.controlBorder,
            shape) else Modifier)
}

/** Quiet tertiary action; callers retain destructive colors and constrained layouts. */
@Composable
internal fun CodexTextAction(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = Ink.parchment, disabledContentColor = Ink.muted),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    TextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled),
        shape = RoundedCornerShape(12.dp), colors = colors,
        contentPadding = contentPadding, interactionSource = press, content = content)
}

@Composable
internal fun CodexIconAction(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable () -> Unit) {
    val press = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).codexActionPress(press, enabled, androidx.compose.foundation.shape.CircleShape),
        interactionSource = press, content = content)
}

/** One input foundation for ordinary fields, protected secrets, search and specialized editors.
 * Clearing, validation, keyboard actions and multiline bounds are deliberate caller choices.
 */
@Composable
internal fun CodexInputField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current, label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    shape: Shape = RoundedCornerShape(13.dp), confirmedValid: Boolean = false) {
    val protected = visualTransformation is PasswordVisualTransformation
    var revealSecret by remember(protected, enabled, readOnly) { mutableStateOf(false) }
    val secretToggle = protected && enabled && !readOnly && trailingIcon == null
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = modifier,
        enabled = enabled, readOnly = readOnly, textStyle = textStyle, label = label,
        placeholder = placeholder, leadingIcon = leadingIcon,
        trailingIcon = if (secretToggle) {{
            CodexTextAction(onClick = { revealSecret = !revealSecret }) {
                Text(if (revealSecret) "隐藏" else "显示", style = MaterialTheme.typography.labelMedium)
            }
        }} else trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = if (protected && revealSecret) VisualTransformation.None else visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines, shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (confirmedValid && !isError) Ink.jade else Ink.copper,
            unfocusedBorderColor = if (confirmedValid && !isError) Ink.jade else Ink.controlBorder,
            focusedLabelColor = Ink.copper, unfocusedLabelColor = Ink.muted,
            cursorColor = Ink.copper, errorBorderColor = Ink.red, errorLabelColor = Ink.red,
            errorCursorColor = Ink.red, disabledBorderColor = Ink.border,
            disabledTextColor = Ink.muted, disabledLabelColor = Ink.muted))
}

/** Filters remain filters; exclusive choices use AtlasChoiceRow / AtlasCompactChoice. */
@Composable
internal fun CodexFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null) {
    val press = remember { MutableInteractionSource() }
    FilterChip(selected = selected, onClick = onClick, label = label,
        modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled), enabled = enabled,
        leadingIcon = leadingIcon, shape = RoundedCornerShape(12.dp), interactionSource = press,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink.copper.copy(alpha = .12f),
            selectedLabelColor = Ink.parchment, selectedLeadingIconColor = Ink.copper,
            labelColor = Ink.muted))
}

@Composable
internal fun CodexCheckBox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
        enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Ink.jade,
            uncheckedColor = Ink.muted, checkmarkColor = Ink.canvas))
}

@Composable
internal fun CodexRadio(selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: RadioButtonColors = RadioButtonDefaults.colors(
        selectedColor = Ink.copper, unselectedColor = Ink.muted)) {
    RadioButton(selected = selected, onClick = onClick, modifier = modifier,
        enabled = enabled, colors = colors)
}

/** Unknown work is still unknown under reduced motion; do not invent a percentage. */
@Composable
internal fun CodexActivityIndicator(modifier: Modifier = Modifier,
    color: Color = Ink.copper, strokeWidth: Dp = 4.dp) {
    val reduced by UiPreferences.reducedMotion.collectAsState()
    if (reduced) CodexIcon(CodexGlyph.TaskRunning, "正在处理",
        modifier = modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        tint = color)
    else CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
}

/** Exclusive compact choice with radio semantics, including the file/folder and config type selectors. */
@Composable
internal fun AtlasExclusiveChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null) {
    val press = remember { MutableInteractionSource() }
    Surface(modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
            interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick),
        color = if (selected) Ink.copper.copy(alpha = .10f) else Ink.canvas,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Ink.copper else if (enabled) Ink.controlBorder else Ink.border)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (leadingIcon != null) leadingIcon()
            CodexRadio(selected = selected, onClick = null, enabled = enabled, modifier = Modifier.size(24.dp))
            label()
        }
    }
}

/** Suggestion inserts text only. It never submits a command. */
@Composable
internal fun CodexSuggestionChip(onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val press = remember { MutableInteractionSource() }
    AssistChip(onClick = onClick, label = label, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled),
        shape = RoundedCornerShape(12.dp), interactionSource = press)
}

/** Whole-row single choice shares native radio semantics and the same press/focus feedback. */
internal fun Modifier.codexSelectable(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    this.codexActionPress(source, enabled).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, interactionSource = source,
        indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
}
