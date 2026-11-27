package io.github.sy220284.codexandroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

internal object LocalNetworkAccess {
    const val permission = Manifest.permission.ACCESS_LOCAL_NETWORK

    fun applies(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** The OS owns the grant. Native tools and custom endpoints share the app UID. */
@Composable
internal fun LocalNetworkAccessCard() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var granted by remember(context) { mutableStateOf(LocalNetworkAccess.granted(context)) }
    var denied by remember(context) { mutableStateOf(false) }
    var settingsError by remember(context) { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        denied = !result
    }
    LifecycleResumeEffect(context) {
        granted = LocalNetworkAccess.granted(context)
        if (granted) denied = false
        onPauseOrDispose { }
    }
    fun openSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            settingsError = true
        }
    }
    MaterialPanel(Modifier.fillMaxWidth().testTag("settings-local-network")) {
        Text("局域网访问", style = MaterialTheme.typography.titleMedium)
        Text("连接同一网络中的模型服务、MCP 或工作站时使用。", color = Ink.muted,
            style = MaterialTheme.typography.bodyMedium)
        Text(if (granted) "已授权" else "未授权", style = MaterialTheme.typography.labelMedium)
        if (settingsError) {
            CodexNotice("请在系统设置的 Codex 应用权限中管理局域网访问。", NoticeTone.Warning)
        }
        val manage = granted || denied && activity?.shouldShowRequestPermissionRationale(LocalNetworkAccess.permission) == false
        CodexSecondaryButton(onClick = {
            if (manage) openSettings() else request.launch(LocalNetworkAccess.permission)
        }, enabled = activity != null) {
            Text(if (manage) "打开系统权限设置" else "允许局域网访问")
        }
    }
}
