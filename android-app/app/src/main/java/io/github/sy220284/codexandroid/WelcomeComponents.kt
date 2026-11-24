package io.github.sy220284.codexandroid

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Contextual first-use action: no wizard, fake login status or inaccessible overlay. */
@Composable
internal fun ChatSetupGuide(hint: ChatSetupHint?, onNavigate: (String) -> Unit) {
    if (hint == null) return
    AtlasSettingsActionRow(
        title = if (hint == ChatSetupHint.Authorize) "完成服务商授权" else "查看模型配置",
        detail = if (hint == ChatSetupHint.Authorize) "登录账号，或选择其他服务商"
            else "当前未返回可用模型",
        icon = if (hint == ChatSetupHint.Authorize) CodexGlyph.AccountCircle else CodexGlyph.Memory,
        onClick = { onNavigate(hint.route) },
        modifier = Modifier.testTag("chat-setup-" + hint.route)
    )
}
