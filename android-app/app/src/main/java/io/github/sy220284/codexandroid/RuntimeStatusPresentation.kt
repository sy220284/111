package io.github.sy220284.codexandroid

internal object RuntimeStatusPresentation {
    fun canReconnect(status: EngineStatus, retiring: Boolean, restarting: Boolean): Boolean =
        status is EngineStatus.Unavailable && !retiring && !restarting

    fun label(status: EngineStatus, retiring: Boolean, restarting: Boolean, retry: Long?): String = when {
        retiring -> "正在回收旧内核"
        restarting -> "正在重新连接"
        status == EngineStatus.Starting -> "正在初始化内核"
        status == EngineStatus.Connected -> "本机内核已连接"
        status is EngineStatus.Unavailable && retry != null -> "等待自动恢复"
        else -> "内核连接异常"
    }
    fun detail(status: EngineStatus, retiring: Boolean, restarting: Boolean, retry: Long?): String = when {
        retiring -> "等待旧进程实际退出，保留草稿和历史，避免两个内核同时运行。"
        restarting -> "正在按顺序关闭旧连接并建立新连接。"
        status == EngineStatus.Starting -> "正在启动本机核心并完成协议握手。"
        status == EngineStatus.Connected -> "运行服务正常，可以继续处理任务。"
        status is EngineStatus.Unavailable && retry != null ->
            "本次恢复等待间隔 ${retry / 1000} 秒；历史与草稿保留，未完成指令不会自动重发。"
        status is EngineStatus.Unavailable -> PresentationPolicy.readableError(status.reason)
        else -> "可在诊断中检查运行状态。"
    }
}
