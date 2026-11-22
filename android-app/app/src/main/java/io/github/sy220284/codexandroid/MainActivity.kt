package io.github.sy220284.codexandroid

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge


class MainActivity : ComponentActivity() {
    // Process-wide session is shared with foreground task execution.
    private val model: CodexViewModel
        get() = (application as CodexApplication).session

    override fun onResume() {
        super.onResume()
        AppUpdateManager.resumeInstall(this)
        model.reconnectIfNeeded()
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
        UiPreferences.initialize(applicationContext)
        // 典雅白界面使用深色系统图标，状态栏与导航栏保持透明白底。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CodexTheme {
                CodexShell(model)
            }
        }
    }
}
