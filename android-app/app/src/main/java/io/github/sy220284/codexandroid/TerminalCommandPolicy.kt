package io.github.sy220284.codexandroid

/** Classify unusually destructive terminal commands before execution. */
internal data class TerminalCommandRisk(val dangerous: Boolean, val warning: String?)

internal object TerminalCommandPolicy {
    private val destructive = listOf(
        Regex("""(?i)(?:^|[;&|]\s*|\bsudo\s+)rm\s+-(?:[a-z]*r[a-z]*f|[a-z]*f[a-z]*r)\b"""),
        Regex("""(?i)\b(?:mkfs(?:\.[a-z0-9]+)?|wipefs)\b"""),
        Regex("""(?i)\bdd\s+[^\n]*\bof\s*=\s*/dev/"""),
        Regex("""(?i)\b(?:curl|wget)\b[^\n]*\|\s*(?:sudo\s+)?(?:sh|bash)\b"""),
        Regex("""(?i)\bchmod\s+-R\s+(?:777|a\+rwx)\b""")
    )

    fun evaluate(command: String): TerminalCommandRisk =
        if (destructive.any { it.containsMatchIn(command) }) {
            TerminalCommandRisk(true,
                "该命令可能递归删除文件、覆盖设备、改变大量权限或运行未经审查的脚本。" +
                    "执行后可能无法恢复。请核对完整命令和工作区，确认你了解风险。")
        } else TerminalCommandRisk(false, null)
}
