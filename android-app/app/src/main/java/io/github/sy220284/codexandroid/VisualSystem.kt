package io.github.sy220284.codexandroid

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Ink {
    val canvas = Color(0xFF101419)
    val recessed = Color(0xFF151B22)
    val panel = Color(0xFF1E262D)
    val elevated = Color(0xFF293139)
    val border = Color(0xFF3B464C)
    val parchment = Color(0xFFF2EDE2)
    val muted = Color(0xFF9AA7A9)
    val copper = Color(0xFFDCA77E)
    val copperDim = Color(0xFF70533F)
    val jade = Color(0xFF95C6AD)
    val red = Color(0xFFE9A094)
}

@Composable
fun CodexTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Ink.copper,
        onPrimary = Ink.canvas,
        primaryContainer = Ink.copperDim,
        background = Ink.canvas,
        onBackground = Ink.parchment,
        surface = Ink.recessed,
        surfaceVariant = Ink.panel,
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
        content = content
    )
}

@Composable
fun MaterialPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Ink.border.copy(alpha = 0.72f)),
        color = Ink.panel,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .028f), Color.Transparent, Color.Black.copy(alpha = .08f))
                    )
                )
                .padding(18.dp),
            content = content
        )
    }
}

@Composable
fun SectionIntro(eyebrow: String, title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(eyebrow.uppercase(), color = Ink.copper, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.4.sp)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle.isNotBlank()) Text(subtitle, color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusPill(text: String, good: Boolean = true) {
    val color = if (good) Ink.jade else Ink.red
    Surface(
        color = color.copy(alpha = .12f),
        border = BorderStroke(1.dp, color.copy(alpha = .27f)),
        shape = RoundedCornerShape(99.dp)
    ) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun DividerLine() {
    HorizontalDivider(color = Ink.border.copy(alpha = .5f), thickness = 1.dp)
}
