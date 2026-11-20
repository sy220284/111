package io.github.sy220284.codexandroid

/** Stable file boundaries and line semantics for the native diff reader. */
data class DiffSection(
    val path: String,
    val lines: List<String>,
    val additions: Int,
    val deletions: Int
) {
    val binary: Boolean get() = lines.any {
        it.startsWith("Binary files ") || it.startsWith("GIT binary patch")
    }
}

object UnifiedDiff {
    fun parse(raw: String): List<DiffSection> {
        if (raw.isBlank()) return emptyList()
        data class SectionBuffer(val path: String, val lines: MutableList<String> = mutableListOf())
        val buffers = mutableListOf<SectionBuffer>()
        var section: SectionBuffer? = null
        raw.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                val path = line.substringAfter(" b/", "").ifBlank { "未命名文件" }
                section = SectionBuffer(path).also { buffers.add(it) }
            }
            if (section == null) {
                section = SectionBuffer("当前变更").also { buffers.add(it) }
            }
            section!!.lines.add(line)
        }
        return buffers.map { file ->
            DiffSection(
                path = file.path,
                lines = file.lines,
                additions = file.lines.count { it.startsWith("+") && !it.startsWith("+++") },
                deletions = file.lines.count { it.startsWith("-") && !it.startsWith("---") }
            )
        }
    }
}
