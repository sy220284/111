package io.github.sy220284.codexandroid

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeSafetyReplyNotice(items: List<TimelineItem>) {
    val states = remember(items) { items.flatMap(AsyncReplyContract::attributes)
        .map(SafetyReplyPresentation::parse).filter { it.helpline || it.trustedContactsCheck }.distinct() }
    if (states.isEmpty()) return
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxWidth().testTag("reply-safety-support"), shape = RoundedCornerShape(12.dp),
        color = Ink.recessed) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (states.any { it.helpline }) {
                Text("获取专业支持", style = MaterialTheme.typography.titleMedium)
                Text("可以联系当地危机支持热线或身边信任的人。如有紧急危险，请拨打当地紧急服务电话。")
                CodexSecondaryButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://findahelpline.com/"))) }
                    catch (failure: android.content.ActivityNotFoundException) { error = "无法打开浏览器，请访问 findahelpline.com" }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查找当地支持热线") }
            }
            if (states.any { it.trustedContactsCheck }) {
                Text("核心要求检查受信联系人设置。此客户端尚未连接官方联系人服务，未发送任何通知。",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            error?.let { ErrorText(it) }
        }
    }
}
