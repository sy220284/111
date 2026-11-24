package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** 统一表单的名称、说明、错误、键盘和清除操作；不改变请求协议。 */
@Composable
internal fun AtlasFormField(value: String, onValueChange: (String) -> Unit,
    label: String, modifier: Modifier = Modifier, placeholder: String = "",
    helper: String = "", error: String? = null, keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true, singleLine: Boolean = true, confirmedValid: Boolean = false) {
    CodexInputField(value, onValueChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = if (placeholder.isNotBlank()) {{ Text(placeholder) }} else null,
        supportingText = if (error != null || helper.isNotBlank()) {{
            Text(error ?: helper, color = if (error != null) Ink.red else Ink.muted)
        }} else null,
        singleLine = singleLine, enabled = enabled, isError = error != null, confirmedValid = confirmedValid,
        shape = RoundedCornerShape(13.dp), keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = if (value.isNotEmpty() && enabled) {{
            CodexIconAction(onClick = { onValueChange("") }, modifier = Modifier.size(48.dp)) {
                CodexIcon(CodexGlyph.Close, "清除" + label)
            }
        }} else null)
}

internal object ProviderAddressPolicy {
    fun valid(raw: String): Boolean {
        val uri = try { java.net.URI(raw.trim()) } catch (_: java.net.URISyntaxException) { return false }
        return !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null &&
            uri.port != 0 && uri.port <= 65535 &&
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
    }
}
