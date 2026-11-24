package io.github.sy220284.codexandroid

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** Installs the local, self-contained MCP management skill before starting Codex. */
internal object McpAgentSkillInstaller {
    private const val assetPath = "skills/111-mcp-service-manager/SKILL.md"
    private const val relativePath = "skills/111-mcp-service-manager/SKILL.md"
    private const val marker = "<!-- 111-managed: mcp-service-manager -->"

    fun install(context: Context, codexHome: File) {
        installManaged(context, codexHome, assetPath, relativePath, marker, "111-mcp-service-manager")
        installManaged(context, codexHome, "skills/111-runtime-inspection/SKILL.md",
            "skills/111-runtime-inspection/SKILL.md", "<!-- 111-managed: runtime-inspection -->", "111-runtime-inspection")
    }

    private fun installManaged(context: Context, codexHome: File, assetPath: String,
        relativePath: String, marker: String, name: String) {
        val instructions = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        require(instructions.contains(marker) &&
            instructions.startsWith("---\nname: $name\n")) {
            "随包 管理技能文件无效"
        }
        val destination = File(codexHome, relativePath)
        val directory = destination.parentFile ?: throw IOException("管理技能目录无效")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("无法创建本机 管理技能目录")
        }
        val existing = if (destination.isFile) destination.readText(Charsets.UTF_8) else null
        if (existing == instructions) return
        if (existing != null && marker !in existing) {
            throw IOException("本机已有不同来源的同名管理技能，请检查目录后再更新")
        }

        val atomic = AtomicFile(destination)
        val stream = atomic.startWrite()
        try {
            stream.write(instructions.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}
