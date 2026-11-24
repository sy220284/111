package io.github.sy220284.codexandroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** The process owns a single Codex core, and releases it after background inactivity. */
class CodexApplication : Application() {
    private val holder = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CodexViewModel(this) }
    val session: CodexViewModel get() = holder.value
    val pendingDeepLink = MutableStateFlow<String?>(null)

    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var activityCount = 0
    private var idleRelease: Job? = null
    private val activities = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            activityCount++
            idleRelease?.cancel()
            idleRelease = null
        }
        override fun onActivityResumed(activity: Activity) {
            // Resume a verified install at the process boundary; the Activity only
            // renders UI and never owns the long-running download/patch operation.
            AppUpdateManager.resumeInstall(activity)
            if (holder.isInitialized()) {
                holder.value.reconnectIfNeeded()
                holder.value.sessions.refreshIfStale()
                holder.value.accountMetrics.foreground(true)
            }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            activityCount = (activityCount - 1).coerceAtLeast(0)
            if (activityCount != 0) return
            if (holder.isInitialized()) holder.value.accountMetrics.foreground(false)
            idleRelease?.cancel()
            idleRelease = maintenance.launch {
                delay(90_000)
                // A running turn, live terminal or pending approval retains the foreground
                // core. Idle background sessions release native child resources.
                if (activityCount == 0 && holder.isInitialized()) {
                    val current = holder.value
                    if (current.globalWorkSnapshot().canDisruptCoreOrData)
                        current.bridge.close()
                }
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        LocalDataArchive.restoreIfPending(this)
        LocalCrashReporter.install(this)
        // Run the initial small cache sweep before any screen can initiate a
        // new download. This avoids racing an asynchronous startup cleanup with
        // an active update attempt or its freshly staged APK.
        try {
            val versionCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
            val retryAfter = UpdateCache.cleanupStale(
                cacheDir, versionCode, System.currentTimeMillis()
            )
            if (retryAfter != null) {
                maintenance.launch(Dispatchers.IO) {
                    delay(retryAfter)
                    try {
                        val currentCode =
                            packageManager.getPackageInfo(packageName, 0).longVersionCode
                        UpdateCache.cleanupStale(
                            cacheDir, currentCode, System.currentTimeMillis()
                        )
                    } catch (error: Exception) {
                        Log.e("CodexUpdateCache", "延迟清理安装交接缓存失败", error)
                    }
                }
            }
        } catch (error: Exception) {
            // A cache cleanup failure must be visible in logs, but must not
            // prevent users from opening the installed application.
            Log.e("CodexUpdateCache", "启动时清理更新缓存失败", error)
        }
        registerActivityLifecycleCallbacks(activities)
    }

    override fun onTerminate() {
        idleRelease?.cancel()
        unregisterActivityLifecycleCallbacks(activities)
        if (holder.isInitialized()) holder.value.bridge.close()
        super.onTerminate()
    }
}
