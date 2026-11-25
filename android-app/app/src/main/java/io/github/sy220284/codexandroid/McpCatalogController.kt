package io.github.sy220284.codexandroid

import android.app.Application
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Keeps the read-only MCP catalog in sync with agent-edited local config.
 * Reload is triggered on observed changes, not by a timer or every screen refresh.
 */
internal class McpCatalogController(private val vm: CodexViewModel) {
    private var appliedConfigVersion = "missing"
    private var startupConfigVersion: String? = null
    private val startupState = MutableStateFlow<List<McpStartupReceipt>>(emptyList())
    val startup = startupState.asStateFlow()

    fun recordStartup(epoch: Long, params: JSONObject) {
        if (epoch != vm.bridge.connectedSession.value) return
        reconcileStartupScope(configVersion())
        val receipt = McpStartupReceipt.parse(epoch, params) ?: return
        startupState.value = McpStartupReceipt.merge(startupState.value, receipt)
    }

    fun clearStartup() {
        startupConfigVersion = null
        startupState.value = emptyList()
    }

    private fun configVersion(): String {
        val config = File(vm.getApplication<Application>().filesDir, "codex-home/config.toml")
        return if (config.isFile) "${config.lastModified()}:${config.length()}" else "missing"
    }

    private fun reconcileStartupScope(version: String) {
        if (startupConfigVersion != version) {
            startupState.value = emptyList()
            startupConfigVersion = version
        }
    }

    fun refresh() {
        if (vm.bridge.status.value != EngineStatus.Connected) return
        val version = configVersion()
        reconcileStartupScope(version)
        if (version == appliedConfigVersion) {
            vm.refresh("mcp", "mcpServerStatus/list",
                CatalogRequestContract.mcpStatus(vm.activeThread.value))
            return
        }
        if ("mcpReload" in vm.loading.value) return
        // The previous tool catalog belongs to the previous configuration.
        // Clear it until the *successful* reload is followed by a fresh list.
        vm.data.value = vm.data.value - "mcp"
        vm.refresh("mcpReload", "config/mcpServer/reload", params = null, onSuccess = {
            appliedConfigVersion = version
            vm.refresh("mcp", "mcpServerStatus/list",
                CatalogRequestContract.mcpStatus(vm.activeThread.value))
        })
    }

    fun reset() {
        clearStartup()
        appliedConfigVersion = "missing"
    }
}
