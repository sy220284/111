package io.github.sy220284.codexandroid

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Debug-only, shell-permission-protected handoff from the normal application process. */
class CiSelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        try {
            require(intent.action == app.packageName + ".ci.SELF_UPDATE")
            check(app.packageManager.canRequestPackageInstalls()) {
                "Install-source permission missing"
            }
            val fixture = File(app.cacheDir, "updates/ci-self-update.apk")
            check(fixture.isFile && fixture.length() > 0L) { "Staged APK missing" }
            val uri = FileProvider.getUriForFile(
                app, "${app.packageName}.updateprovider", fixture
            )
            app.startActivity(AppUpdateManager.systemInstallIntent(uri))
            resultCode = Activity.RESULT_OK
        } catch (error: Exception) {
            resultCode = Activity.RESULT_CANCELED
            resultData = error.message ?: error.javaClass.simpleName
        }
    }
}
