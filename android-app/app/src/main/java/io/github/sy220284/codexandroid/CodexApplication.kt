package io.github.sy220284.codexandroid

import android.app.Activity
import android.app.Application
import android.os.Bundle
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
    private var activityCount = 0
    private var idleRelease: Job? = null
    private val activities = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            activityCount++
            idleRelease?.cancel()
            idleRelease = null
        }
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {
            activityCount = (activityCount - 1).coerceAtLeast(0)
            if (activityCount != 0) return
            idleRelease?.cancel()
            idleRelease = maintenance.launch {
                delay(90_000)
                // A running turn, live terminal or pending approval retains the foreground
                // core. Idle background sessions release native child resources.
                if (activityCount == 0 && holder.isInitialized()) {
                    val current = holder.value
                    if (!current.running.value && !current.terminalSession.value.running &&
                        current.bridge.prompts.value.isEmpty()) current.bridge.close()
                }
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        LocalDataArchive.restoreIfPending(this)
        registerActivityLifecycleCallbacks(activities)
    }

    override fun onTerminate() {
        idleRelease?.cancel()
        unregisterActivityLifecycleCallbacks(activities)
        if (holder.isInitialized()) holder.value.bridge.close()
        super.onTerminate()
    }
}
