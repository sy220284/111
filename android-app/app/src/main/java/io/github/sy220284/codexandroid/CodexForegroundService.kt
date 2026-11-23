package io.github.sy220284.codexandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The foreground notification mirrors the process-wide Codex session.
 * It remains available while an approval is pending, even if a turn is not
 * currently emitting tokens. Its stop action uses the same validated VM RPC.
 */
internal object ForegroundTaskPolicy {
    fun shouldKeepAlive(running: Boolean, terminalRunning: Boolean, approvals: Int): Boolean =
        running || terminalRunning || approvals > 0

    fun shouldReconnect(
        connected: Boolean, running: Boolean, terminalRunning: Boolean, approvals: Int
    ): Boolean = !connected && shouldKeepAlive(running, terminalRunning, approvals)
}

class CodexForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var reconnectWatcher: Job? = null

    private data class TaskNotificationState(
        val running: Boolean,
        val threads: Int,
        val terminalRunning: Boolean,
        val approvals: Int,
        val connected: Boolean,
        val events: Int,
        val canStop: Boolean
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Codex 任务与审批", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "长任务进度、审批提醒和停止操作" })

        // A foreground notification must exist immediately, before collecting flows.
        showNotification(TaskNotificationState(true, 1, false, 0, true, 0, false), foreground = true)

        val session = (application as CodexApplication).session
        watch = scope.launch {
            val activeCount = combine(
                session.running, session.threadExecutions, session.firstThreadStarting
            ) { visibleRunning, executions, starting ->
                executions.size + if (starting || (visibleRunning && executions.isEmpty())) 1 else 0
            }
            val activeWork = combine(
                activeCount, session.terminalSession, session.bridge.prompts
            ) { count, terminal, prompts -> Triple(count, terminal.running, prompts.size) }
            combine(
                activeWork, session.bridge.status, session.timeline, session.activeTurn
            ) { work, status, timeline, turn ->
                TaskNotificationState(
                    running = work.first > 0,
                    threads = work.first,
                    terminalRunning = work.second,
                    approvals = work.third,
                    connected = status == EngineStatus.Connected,
                    events = timeline.size,
                    canStop = (session.running.value && !turn.isNullOrBlank()) || work.second
                )
            }.distinctUntilChanged().collectLatest { state ->
                if (!ForegroundTaskPolicy.shouldKeepAlive(
                        state.running, state.terminalRunning, state.approvals)) {
                    reconnectWatcher?.cancel()
                    stopSelf()
                } else {
                    showNotification(state, foreground = false)
                    // A pending approval is a live interaction even when its turn is idle.
                    // Reconnect while the foreground service is legitimately active;
                    // exponential backoff avoids thrashing a failed native child.
                    if (ForegroundTaskPolicy.shouldReconnect(
                            state.connected, state.running,
                            state.terminalRunning, state.approvals
                        ) && reconnectWatcher?.isActive != true) {
                        reconnectWatcher = scope.launch {
                            var interval = 2_000L
                            while (isActive && ForegroundTaskPolicy.shouldKeepAlive(
                                    session.threadExecutions.value.isNotEmpty() ||
                                        session.running.value || session.firstThreadStarting.value,
                                    session.terminalSession.value.running,
                                    session.bridge.prompts.value.size
                                ) && session.bridge.status.value != EngineStatus.Connected) {
                                delay(interval)
                                session.reconnectIfNeeded()
                                interval = (interval * 2).coerceAtMost(60_000L)
                            }
                        }
                    }
                    if (state.connected) reconnectWatcher?.cancel()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A notification action is an explicit user request; use the same
            // thread/turn validation and RPC as the in-app stop control.
            val session = (application as CodexApplication).session
            if (session.running.value && !session.activeTurn.value.isNullOrBlank()) {
                session.interrupt()
            } else if (session.terminalSession.value.running) {
                session.stopTerminalCommand()
            }
        }
        return START_NOT_STICKY
    }

    private fun showNotification(state: TaskNotificationState, foreground: Boolean) {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openIntent = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = Intent(this, CodexForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopIntent = PendingIntent.getService(
            this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = when {
            !state.connected -> "Codex 运行核心连接中断"
            state.approvals > 0 -> "Codex 有 ${state.approvals} 项操作等待确认"
            state.threads > 1 -> "Codex 正在执行 ${state.threads} 条会话任务"
            state.terminalRunning && state.threads == 0 -> "Codex 终端命令正在执行"
            else -> "Codex 正在执行任务"
        }
        val detail = when {
            !state.connected -> "点按返回应用，重新连接并检查执行结果"
            state.approvals > 0 -> "点击返回任务对话，查看内容并决定是否允许"
            state.threads > 1 -> "多会话任务持续运行，点按查看并切换会话"
            state.terminalRunning && state.threads == 0 -> "终端仍在运行，点按查看输出或停止命令"
            else -> "已记录 ${state.events} 条执行事件，点按查看详情"
        }
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_codex_status)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setProgress(0, 0,
                (state.running || state.terminalRunning) && state.approvals == 0 && state.connected)

        if (state.canStop && state.connected) {
            builder.addAction(Notification.Action.Builder(
                R.drawable.ic_codex_stop,
                if (state.terminalRunning && state.threads == 0) "停止终端" else "停止任务",
                stopIntent
            ).build())
        }
        val note = builder.build()
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, note)
            }
        } else {
            getSystemService(NotificationManager::class.java).notify(ID, note)
        }
    }

    override fun onDestroy() {
        watch?.cancel()
        reconnectWatcher?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 1101
        private const val CHANNEL = "codex-active-turn"
        private const val ACTION_STOP = "io.github.sy220284.codexandroid.STOP_ACTIVE_TURN"

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, CodexForegroundService::class.java)
            )
        }
    }
}
