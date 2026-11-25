package io.github.sy220284.codexandroid

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge


class MainActivity : ComponentActivity() {
    // Process-wide session is shared with foreground task execution.
    private val model: CodexViewModel
        get() = (application as CodexApplication).session

    private var authorized by mutableStateOf(false)
    private var workspaceReady by mutableStateOf(false)
    private var workspaceFailure by mutableStateOf("")
    private var preparation: Job? = null
    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshWorkspaceAccess()
    }
    private val storageSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshWorkspaceAccess()
    }
    private var permissionError by mutableStateOf("")

    internal val workspaceStartupPhase: String get() = when {
        !authorized -> "permission_required"
        workspaceReady -> "ready"
        workspaceFailure.isNotBlank() -> "failed"
        else -> "preparing"
    }

    /** Workspace preparation belongs to the Activity lifecycle, not a Compose frame. */
    private fun refreshWorkspaceAccess(force: Boolean = false) {
        val granted = WorkspaceStorage.authorized(this)
        val changed = granted != authorized
        authorized = granted
        if (!granted) {
            preparation?.cancel()
            workspaceReady = false
            workspaceFailure = ""
            return
        }
        if (!force && !changed &&
            (workspaceReady || preparation?.isActive == true || workspaceFailure.isNotBlank())) return
        preparation?.cancel()
        workspaceReady = false
        workspaceFailure = ""
        preparation = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    (application as CodexApplication).prepareWorkspace()
                }
                UiPreferences.initialize(applicationContext)
                workspaceReady = true
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.e("CodexWorkspace", "工作区初始化失败", error)
                workspaceFailure = error.message ?: "工作区初始化失败，请检查手机存储"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWorkspaceAccess()
    }

    private fun requestStorageAccess() {
        permissionError = ""
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                storageSettings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: android.content.ActivityNotFoundException) {
                try { storageSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                catch (error: android.content.ActivityNotFoundException) {
                    permissionError = "系统未提供文件授权页面，请在设置中手动授权 Codex。"
                }
            }
        } else {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptDeepLink(intent)
    }

    private fun acceptDeepLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val id = CodexDeepLink.threadId(intent.dataString) ?: return
        (application as CodexApplication).pendingDeepLink.value = id
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptDeepLink(intent)
        refreshWorkspaceAccess()
        // 典雅白界面使用深色系统图标，状态栏与导航栏保持透明白底。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val dark by UiPreferences.darkMode.collectAsState()
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark)
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = if (dark)
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT)
                )
            }
            CodexTheme {
                if (authorized && workspaceReady) CodexShell(model)
                else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("手机工作区", style = MaterialTheme.typography.headlineSmall)
                        Text(WorkspaceStorage.DISPLAY_ROOT, style = MaterialTheme.typography.titleMedium)
                        when {
                            !authorized -> {
                                Text("授权后，Codex、终端和手机文件管理器可以直接操作同一份项目文件。账号和运行配置保存在应用私有目录。",
                                    style = MaterialTheme.typography.bodyMedium)
                                if (permissionError.isNotBlank()) ErrorText(permissionError)
                                CodexPrimaryButton(onClick = ::requestStorageAccess) { Text("授权文件访问") }
                            }
                            workspaceFailure.isNotBlank() -> {
                                ErrorText(workspaceFailure)
                                CodexPrimaryButton(onClick = { refreshWorkspaceAccess(force = true) }) { Text("重新检查") }
                            }
                            else -> AtlasOperationProgress("正在准备工作区并检查原有项目")
                        }
                    }
                }
            }
        }
    }
}
