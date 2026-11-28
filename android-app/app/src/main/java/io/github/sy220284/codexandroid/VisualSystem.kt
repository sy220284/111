package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private object InkLight {
    val canvas = Color(0xFFFFFFFF)
    val recessed = Color(0xFFF6F7F8)
    val panel = Color(0xFFFFFFFF)
    val elevated = Color(0xFFFCFBFA)
    val border = Color(0xFFD8DEE1)
    val controlBorder = Color(0xFF7B8992)
    val parchment = Color(0xFF263139)
    val muted = Color(0xFF56646D)
    val coral = Color(0xFFB94037) // Primary emphasis; contrast on all four light work surfaces
    val coralSoft = Color(0xFFFF806F) // Reference hue for non-text decoration
    val copper = Color(0xFF805A3F)
    val copperDim = Color(0xFFB8A18D)
    val jade = Color(0xFF246F54)
    val warning = Color(0xFF8A6437)
    val red = Color(0xFFAC363A)
}

private object InkDark {
    val canvas = Color(0xFF111A22)
    val recessed = Color(0xFF0D141B)
    val panel = Color(0xFF19232C)
    val elevated = Color(0xFF202D38)
    val border = Color(0xFF344450)
    val controlBorder = Color(0xFF81929E)
    val parchment = Color(0xFFF0F2F3)
    val muted = Color(0xFFB3C0C8)
    val coral = Color(0xFFFF9386)
    val coralSoft = Color(0xFFFFAE9F)
    val copper = Color(0xFFE4B08D)
    val copperDim = Color(0xFFB5927B)
    val jade = Color(0xFF80D0AA)
    val warning = Color(0xFFE4C080)
    val red = Color(0xFFFF8C93)
}

/** Terminal surfaces retain dark contrast in both application themes. */
internal object RuntimeConsolePalette {
    val background get() = InkDark.recessed
    val foreground get() = InkDark.parchment
    val secondary get() = InkDark.muted
}

object Ink {
    val canvas: Color get() = if (UiPreferences.darkMode.value) InkDark.canvas else InkLight.canvas
    val recessed: Color get() = if (UiPreferences.darkMode.value) InkDark.recessed else InkLight.recessed
    val panel: Color get() = if (UiPreferences.darkMode.value) InkDark.panel else InkLight.panel
    val elevated: Color get() = if (UiPreferences.darkMode.value) InkDark.elevated else InkLight.elevated
    val border: Color get() = if (UiPreferences.darkMode.value) InkDark.border else InkLight.border
    val controlBorder: Color get() = if (UiPreferences.darkMode.value) InkDark.controlBorder else InkLight.controlBorder
    val parchment: Color get() = if (UiPreferences.darkMode.value) InkDark.parchment else InkLight.parchment
    val muted: Color get() = if (UiPreferences.darkMode.value) InkDark.muted else InkLight.muted
    val coral: Color get() = if (UiPreferences.darkMode.value) InkDark.coral else InkLight.coral
    val coralSoft: Color get() = if (UiPreferences.darkMode.value) InkDark.coralSoft else InkLight.coralSoft
    val copper: Color get() = if (UiPreferences.darkMode.value) InkDark.copper else InkLight.copper
    val copperDim: Color get() = if (UiPreferences.darkMode.value) InkDark.copperDim else InkLight.copperDim
    val jade: Color get() = if (UiPreferences.darkMode.value) InkDark.jade else InkLight.jade
    val warning: Color get() = if (UiPreferences.darkMode.value) InkDark.warning else InkLight.warning
    val red: Color get() = if (UiPreferences.darkMode.value) InkDark.red else InkLight.red
}

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
    val fontScale by UiPreferences.fontScale.collectAsStateWithLifecycle()
    val darkMode by UiPreferences.darkMode.collectAsStateWithLifecycle()
    val scheme = if (darkMode) darkColorScheme(
        primary = Ink.coral,
        onPrimary = Ink.canvas,
        primaryContainer = Ink.canvas,
        onPrimaryContainer = Ink.parchment,
        secondaryContainer = Ink.canvas,
        onSecondaryContainer = Ink.parchment,
        tertiaryContainer = Ink.canvas,
        onTertiaryContainer = Ink.parchment,
        background = Ink.canvas,
        onBackground = Ink.parchment,
        surface = Ink.recessed,
        surfaceVariant = Ink.canvas,
        surfaceTint = Color.Transparent,
        surfaceDim = Ink.canvas,
        surfaceBright = Ink.canvas,
        surfaceContainerLowest = Ink.canvas,
        surfaceContainerLow = Ink.recessed,
        surfaceContainer = Ink.panel,
        surfaceContainerHigh = Ink.elevated,
        surfaceContainerHighest = Ink.elevated,
        outlineVariant = Ink.border,
        errorContainer = Ink.canvas,
        onErrorContainer = Ink.red,
        onSurface = Ink.parchment,
        onSurfaceVariant = Ink.muted,
        outline = Ink.controlBorder,
        secondary = Ink.jade,
        error = Ink.red
    ) else lightColorScheme(
        primary = Ink.coral,
        onPrimary = Ink.canvas,
        primaryContainer = Ink.canvas,
        onPrimaryContainer = Ink.parchment,
        secondaryContainer = Ink.canvas,
        onSecondaryContainer = Ink.parchment,
        tertiaryContainer = Ink.canvas,
        onTertiaryContainer = Ink.parchment,
        background = Ink.canvas,
        onBackground = Ink.parchment,
        surface = Ink.recessed,
        surfaceVariant = Ink.canvas,
        surfaceTint = Color.Transparent,
        surfaceDim = Ink.canvas,
        surfaceBright = Ink.canvas,
        surfaceContainerLowest = Ink.canvas,
        surfaceContainerLow = Ink.recessed,
        surfaceContainer = Ink.panel,
        surfaceContainerHigh = Ink.elevated,
        surfaceContainerHighest = Ink.elevated,
        outlineVariant = Ink.border,
        errorContainer = Ink.canvas,
        onErrorContainer = Ink.red,
        onSurface = Ink.parchment,
        onSurfaceVariant = Ink.muted,
        outline = Ink.controlBorder,
        secondary = Ink.jade,
        error = Ink.red
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            displaySmall = androidx.compose.ui.text.TextStyle(fontSize = (34 * fontScale).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = (26 * fontScale).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = (20 * fontScale).sp, lineHeight = (27 * fontScale).sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = (16 * fontScale).sp, lineHeight = (22 * fontScale).sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = (15 * fontScale).sp, lineHeight = (23 * fontScale).sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp, lineHeight = (21 * fontScale).sp),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = (12 * fontScale).sp, lineHeight = (18 * fontScale).sp, fontWeight = FontWeight.Medium),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = (12 * fontScale).sp, lineHeight = (17 * fontScale).sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = (12 * fontScale).sp, lineHeight = (18 * fontScale).sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp, lineHeight = (20 * fontScale).sp, fontWeight = FontWeight.Medium),
            titleSmall = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp, lineHeight = (20 * fontScale).sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = {
            // Pages outside a Material Surface inherit the correct dark foreground.
            CompositionLocalProvider(LocalContentColor provides Ink.parchment) {
                ProvideCodexViewport(content)
            }
        }
    )
}

@Composable
fun MaterialPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compact by UiPreferences.compact.collectAsStateWithLifecycle()
    val softMaterial by UiPreferences.softMaterial.collectAsStateWithLifecycle()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (compact) 16.dp else 22.dp),
        border = BorderStroke(if (softMaterial) .8.dp else 1.dp,
            Ink.border.copy(alpha = if (softMaterial) .5f else .72f)),
        color = Ink.panel,
        shadowElevation = if (softMaterial) 1.dp else 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            content = content
        )
    }
}

/** Business content is open by default; a reading surface is reserved for artifacts and output.
 * Keep MaterialPanel for independent decisions and controls that really need containment. */
internal enum class WorkSectionKind { Content, Reading }

@Composable
internal fun WorkSection(
    modifier: Modifier = Modifier,
    kind: WorkSectionKind = WorkSectionKind.Content,
    content: @Composable ColumnScope.() -> Unit
) {
    val compact by UiPreferences.compact.collectAsStateWithLifecycle()
    val gap = if (compact) 8.dp else 12.dp
    if (kind == WorkSectionKind.Content) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), content = content)
    } else {
        Surface(modifier.fillMaxWidth(), color = Ink.recessed, contentColor = Ink.parchment,
            shape = RoundedCornerShape(12.dp), tonalElevation = 0.dp) {
            Column(Modifier.padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(gap), content = content)
        }
    }
}

val LocalWorkPageTitle = compositionLocalOf { "" }
internal val LocalWorkPageExplanation = compositionLocalOf<((String?) -> Unit)?> { null }

/** 页面导语只保留标题；操作说明按需进入二级弹层。不再保留无效英文分类参数。 */
@Composable
fun SectionIntro(title: String, subtitle: String = "") {
    val showTitle = title != LocalWorkPageTitle.current
    var showExplanation by remember(title, subtitle) { mutableStateOf(false) }
    val registerExplanation = LocalWorkPageExplanation.current
    if (!showTitle && registerExplanation != null) {
        DisposableEffect(registerExplanation, subtitle) {
            registerExplanation(subtitle.takeIf { it.isNotBlank() })
            onDispose { registerExplanation(null) }
        }
        return
    }
    if (showTitle || subtitle.isNotBlank()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showTitle) {
                Text(title, color = Ink.parchment, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (subtitle.isNotBlank()) {
                CodexIconAction(
                    onClick = { showExplanation = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    CodexIcon(CodexGlyph.Info, contentDescription = "查看" + title + "说明",
                        tint = Ink.muted)
                }
            }
        }
    }
    if (showExplanation) CodexInfoDialog(title = title, onDismiss = { showExplanation = false }) { Text(subtitle) }
}

enum class StatusTone { Neutral, Info, Success, Warning, Danger }

@Composable
fun StatusPill(text: String, good: Boolean = true, tone: StatusTone = if (good) StatusTone.Success else StatusTone.Warning) {
    val color = when (tone) {
        StatusTone.Neutral -> Ink.muted
        StatusTone.Info -> Ink.copper
        StatusTone.Success -> Ink.jade
        StatusTone.Warning -> Ink.warning
        StatusTone.Danger -> Ink.red
    }
    Surface(color = Ink.canvas, border = BorderStroke(1.dp, color.copy(alpha = .46f)),
        shape = RoundedCornerShape(99.dp)) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** 主操作使用深墨实心，对应真正需要完成的当前决策。 */
@Composable
fun CodexPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(13.dp),
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = Ink.parchment, contentColor = Ink.canvas,
        disabledContainerColor = Ink.border, disabledContentColor = Ink.muted),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled && !busy, shape)
            .semantics { if (busy) stateDescription = "正在处理" },
        enabled = enabled && !busy, shape = shape, interactionSource = press,
        colors = colors, contentPadding = contentPadding
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Keep the original label and measured width; an in-flight operation cannot
            // move neighboring controls. The button retains its accessible action name.
            Row(Modifier.alpha(if (busy) 0f else 1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center, content = content)
            if (busy) CodexActivityIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink.muted)
        }
    }
}

/** 次级操作以中性描边表达，适用于取消、拒绝和辅助流程。 */
@Composable
fun CodexSecondaryButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Ink.canvas, contentColor = Ink.parchment, disabledContentColor = Ink.muted),
    content: @Composable RowScope.() -> Unit
) {
    val press = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).codexActionPress(press, enabled),
        shape = RoundedCornerShape(13.dp),
        interactionSource = press,
        colors = colors,
        border = BorderStroke(1.dp, if (enabled) Ink.controlBorder else Ink.border),
        content = content
    )
}

/** 开启以实色轨道与位置共同表达，关闭保留清晰描边。 */
@Composable
fun WhiteSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Ink.copper,
            uncheckedTrackColor = Ink.canvas,
            disabledCheckedTrackColor = Ink.canvas,
            disabledUncheckedTrackColor = Ink.canvas,
            checkedThumbColor = Ink.canvas,
            uncheckedThumbColor = Ink.muted,
            checkedBorderColor = Ink.copper,
            uncheckedBorderColor = Ink.controlBorder
        )
    )
}

@Composable
fun DividerLine() {
    HorizontalDivider(color = Ink.border.copy(alpha = .5f), thickness = 1.dp)
}
