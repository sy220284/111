package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val canvas = Color(0xFFFFFFFF)
    val recessed = Color(0xFFFFFFFF)
    val panel = Color(0xFFFFFFFF)
    val elevated = Color(0xFFFFFFFF)
    val border = Color(0xFFD8DEE1)
    val parchment = Color(0xFF263139)
    val muted = Color(0xFF56646D)
    val copper = Color(0xFF805A3F)
    val copperDim = Color(0xFFB8A18D)
    val jade = Color(0xFF246F54)
    val red = Color(0xFFAC363A)
}

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Ink.copper,
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
            displaySmall = androidx.compose.ui.text.TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
            headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
            titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
        border = BorderStroke(1.dp, Ink.border.copy(alpha = 0.72f)),
        color = Ink.panel,
        shadowElevation = if (softMaterial) 2.dp else 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 12.dp else 17.dp),
            content = content
        )
    }
}

val LocalWorkPageTitle = compositionLocalOf { "" }

@Composable
fun SectionIntro(eyebrow: String, title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (title != LocalWorkPageTitle.current) {
            Text(title, color = Ink.parchment, style = MaterialTheme.typography.headlineSmall)
        }
        if (subtitle.isNotBlank()) Text(subtitle, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

enum class StatusTone { Neutral, Info, Success, Warning, Danger }

@Composable
fun StatusPill(text: String, good: Boolean = true, tone: StatusTone = if (good) StatusTone.Success else StatusTone.Warning) {
    val color = when (tone) {
        StatusTone.Neutral -> Ink.muted
        StatusTone.Info -> Ink.copper
        StatusTone.Success -> Ink.jade
        StatusTone.Warning, StatusTone.Danger -> Ink.red
    }
    Surface(color = Ink.canvas, border = BorderStroke(1.dp, color.copy(alpha = .46f)),
        shape = RoundedCornerShape(99.dp)) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** 重点操作以白底、暖铜文字和较强描边呈现。 */
@Composable
fun CodexPrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(13.dp),
    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        border = BorderStroke(1.5.dp, if (enabled) Ink.copper else Ink.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Ink.canvas,
            contentColor = Ink.copper, disabledContainerColor = Ink.canvas,
            disabledContentColor = Ink.muted), content = content)
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
