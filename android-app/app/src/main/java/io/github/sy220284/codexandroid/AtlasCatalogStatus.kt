package io.github.sy220284.codexandroid

import androidx.compose.runtime.*

/** 单一页面读取状态：首次加载、断连、读取失败、刷新、旧数据可读。 */
internal enum class CatalogReadStage { FirstLoad, Connecting, Disconnected, Failed, Refreshing, Ready, Unread }

internal object CatalogReadPolicy {
    fun stage(hasSnapshot: Boolean, connecting: Boolean, connected: Boolean,
        loading: Boolean, failed: Boolean): CatalogReadStage = when {
        hasSnapshot && loading -> CatalogReadStage.Refreshing
        hasSnapshot -> CatalogReadStage.Ready
        connecting -> CatalogReadStage.Connecting
        !connected -> CatalogReadStage.Disconnected
        loading -> CatalogReadStage.FirstLoad
        failed -> CatalogReadStage.Failed
        else -> CatalogReadStage.Unread
    }
}

/** 专用目录页面统一承接读取反馈；调用者仅负责真实业务内容和写入。 */
@Composable
internal fun AtlasCatalogStatus(
    vm: CodexViewModel, key: String, subject: String, onRefresh: () -> Unit,
    onDiagnostics: (() -> Unit)? = null
) {
    val data by vm.data.collectAsState()
    val errors by vm.errors.collectAsState()
    val loading by vm.loading.collectAsState()
    val status by vm.bridge.status.collectAsState()
    val stage = CatalogReadPolicy.stage(data[key] != null, status == EngineStatus.Starting,
        status == EngineStatus.Connected, key in loading, errors[key] != null)
    when (stage) {
        CatalogReadStage.FirstLoad -> AtlasLoadingResults("正在读取" + subject, placeholderRows = 2)
        CatalogReadStage.Connecting -> AtlasOperationProgress("正在连接运行核心")
        CatalogReadStage.Disconnected -> CodexRecoveryState("连接后可查看" + subject,
            "连接运行核心后读取实际数据。", "重新连接", { vm.connect() }, onDiagnostics,
            artworkRes = R.drawable.ic_empty_connection)
        CatalogReadStage.Failed -> CodexRecoveryState(subject + "读取失败",
            PresentationPolicy.readableError(errors.getValue(key)), "重新读取", onRefresh, onDiagnostics)
        CatalogReadStage.Refreshing -> AtlasOperationProgress("正在刷新" + subject)
        CatalogReadStage.Ready -> if (errors[key] != null) AtlasFieldFeedback(
            "刷新未完成，保留上次读取的数据：" + PresentationPolicy.readableError(errors.getValue(key)), isError = true)
        CatalogReadStage.Unread -> CodexRecoveryState(subject + "尚未读取",
            "可重新读取最新数据。", "读取数据", onRefresh, onDiagnostics,
            artworkRes = R.drawable.ic_empty_content)
    }
}
