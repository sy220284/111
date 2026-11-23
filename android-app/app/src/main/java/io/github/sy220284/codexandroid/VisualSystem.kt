package io.github.sy220284.codexandroid

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InfoOutlined
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private object InkLight {
    val canvas = Color(0xFFFFFFFF)
    val recessed = Color(0xFFFFFFFF)
    val panel = Color(0xFFFFFFFF)
    val elevated = Color(0xFFFFFFFF)
    val border = Color(0xFFD8DEE1)
    val parchment = Color(0xFF263139)
    val muted = Color(0xFF56646D)
    val coral = Color(0xFFC8483E) // Accessible foreground on white-icon actions (contrast 4.7:1)
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

object Ink {
    val canvas: Color get() = if (UiPreferences.darkMode.value) InkDark.canvas else InkLight.canvas
    val recessed: Color get() = if (UiPreferences.darkMode.value) InkDark.recessed else InkLight.recessed
    val panel: Color get() = if (UiPreferences.darkMode.value) InkDark.panel else InkLight.panel
    val elevated: Color get() = if (UiPreferences.darkMode.value) InkDark.elevated else InkLight.elevated
    val border: Color get() = if (UiPreferences.darkMode.value) InkDark.border else InkLight.border
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
    val fontScale by UiPreferences.fontScale.collectAsState()
    val darkMode by UiPreferences.darkMode.collectAsState()
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
        surfaceContainerLow = Ink.canvas,
        surfaceContainer = Ink.canvas,
        surfaceContainerHigh = Ink.canvas,
        surfaceContainerHighest = Ink.canvas,
        outlineVariant = Ink.border,
        errorContainer = Ink.canvas,
        onErrorContainer = Ink.red,
        onSurface = Ink.parchment,
        onSurfaceVariant = Ink.muted,
        outline = Ink.border,
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
        surfaceContainerLow = Ink.canvas,
        surfaceContainer = Ink.canvas,
        surfaceContainerHigh = Ink.canvas,
        surfaceContainerHighest = Ink.canvas,
        outlineVariant = Ink.border,
        errorContainer = Ink.canvas,
        onErrorContainer = Ink.red,
        onSurface = Ink.parchment,
        onSurfaceVariant = Ink.muted,
        outline = Ink.border,
        secondary = Ink.jade,
        error = Ink.red
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            displaySmall = androidx.compose.ui.text.TextStyle(fontSize = (34 * fontScale).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = (26 * fontScale).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = (20 * fontScale).sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = (16 * fontScale).sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = (15 * fontScale).sp, lineHeight = (23 * fontScale).sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp, lineHeight = (21 * fontScale).sp),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = (12 * fontScale).sp, fontWeight = FontWeight.Medium),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = (11 * fontScale).sp),
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = (12 * fontScale).sp),
            labelLarge = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp),
            titleSmall = androidx.compose.ui.text.TextStyle(fontSize = (14 * fontScale).sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp)
        ),
        content = {
            // Pages outside a Material Surface inherit the correct dark foreground.
            CompositionLocalProvider(LocalContentColor provides Ink.parchment) {
                content()
            }
        }
    )
}

@Composable
fun MaterialPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val compact by UiPreferences.compact.collectAsState()
    val softMaterial by UiPreferences.softMaterial.collectAsState()
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

val LocalWorkPageTitle = compositionLocalOf { "" }

/** 页面导语只保留标题；操作说明按需进入二级弹层。英文分类标记仅供历史调用兼容。 */
@Composable
fun SectionIntro(@Suppress("UNUSED_PARAMETER") eyebrow: String, title: String, subtitle: String = "") {
    val showTitle = title != LocalWorkPageTitle.current
    var showExplanation by remember(title, subtitle) { mutableStateOf(false) }
    if (showTitle || subtitle.isNotBlank()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showTitle) {
                Text(title, color = Ink.parchment, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (subtitle.isNotBlank()) {
                IconButton(
                    onClick = { showExplanation = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(Icons.Outlined.InfoOutlined, contentDescription = "查看" + title + "说明",
                        tint = Ink.muted)
                }
            }
        }
    }
    if (showExplanation) AlertDialog(
        onDismissRequest = { showExplanation = false },
        title = { Text(title) },
        text = { Text(subtitle) },
        confirmButton = {
            TextButton(onClick = { showExplanation = false }) { Text("关闭") }
        }
    )
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

/** 重点操作以白底、暖铜文字和较强描边呈现。 */
@Composable
fun CodexPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(13.dp),
    content: @Composable RowScope.() -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val reduced by UiPreferences.reducedMotion.collectAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && !busy && pressed && !reduced) .985f else 1f,
        animationSpec = tween(CodexMotion.duration(CodexMotion.feedback, reduced)),
        label = "primary-action-press"
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        enabled = enabled && !busy, shape = shape, interactionSource = press,
        border = BorderStroke(1.3.dp, if (enabled && !busy) Ink.coral else Ink.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Ink.canvas, contentColor = Ink.coral,
            disabledContainerColor = Ink.canvas, disabledContentColor = Ink.muted)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink.muted)
            Spacer(Modifier.width(9.dp))
            Text("正在处理…")
        } else content()
    }
}

/** 所有开关轨道保持白色，状态通过边框与滑块辨认。 */
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
            checkedTrackColor = Ink.canvas,
            uncheckedTrackColor = Ink.canvas,
            disabledCheckedTrackColor = Ink.canvas,
            disabledUncheckedTrackColor = Ink.canvas,
            checkedThumbColor = Ink.copper,
            uncheckedThumbColor = Ink.muted,
            checkedBorderColor = Ink.copper,
            uncheckedBorderColor = Ink.border
        )
    )
}

@Composable
fun DividerLine() {
    HorizontalDivider(color = Ink.border.copy(alpha = .5f), thickness = 1.dp)
}
