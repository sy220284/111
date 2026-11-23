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
        vm.refresh("mcp", "mcpServerStatus/list",
            CatalogRequestContract.mcpStatus(vm.activeThread.value))

        val config = File(vm.getApplication<Application>().filesDir,
            "codex-home/config.toml")
        val version = if (config.isFile) "${config.lastModified()}:${config.length()}"
            else "missing"
        if (version == appliedConfigVersion || "mcpReload" in vm.loading.value) return
        vm.refresh("mcpReload", "config/mcpServer/reload", onSuccess = {
            appliedConfigVersion = version
            vm.refresh("mcp", "mcpServerStatus/list",
                CatalogRequestContract.mcpStatus(vm.activeThread.value))
        })
    }

    fun reset() {
        appliedConfigVersion = "missing"
    }
}
