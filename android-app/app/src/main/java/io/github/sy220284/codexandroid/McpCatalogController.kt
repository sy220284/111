package io.github.sy220284.codexandroid

import android.app.Application
import java.io.File

/**
 * Keeps the read-only MCP catalog in sync with agent-edited local config.
 * Reload is triggered on observed changes, not by a timer or every screen refresh.
 */
internal class McpCatalogController(private val vm: CodexViewModel) {
    private var appliedConfigVersion = "missing"

    fun refresh() {
        if (vm.bridge.status.value != EngineStatus.Connected) return
        val config = File(vm.getApplication<Application>().filesDir,
            "codex-home/config.toml")
        val version = if (config.isFile) "${config.lastModified()}:${config.length()}"
            else "missing"
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
        appliedConfigVersion = "missing"
    }
}
