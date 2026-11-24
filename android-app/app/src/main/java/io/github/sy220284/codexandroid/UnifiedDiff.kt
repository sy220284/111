package io.github.sy220284.codexandroid

/** Stable file boundaries and line semantics for the native diff reader. */
data class DiffSection(
    val path: String,
    val lines: List<String>,
    val additions: Int,
    val deletions: Int
) {
    val binary: Boolean = lines.any {
        it.startsWith("Binary files ") || it.startsWith("GIT binary patch")
    }
}

data class DiffLineNumbers(val old: Int?, val new: Int?)

object UnifiedDiff {
    private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

    /** 逐行映射到旧文件和新文件的真实行号；文件头与提示行留白。 */
    fun annotateLines(lines: List<String>): List<DiffLineNumbers> {
        var old = 0
        var new = 0
        var inHunk = false
        return lines.map { line ->
            val header = hunkHeader.find(line)
            if (header != null) {
                val oldStart = header.groupValues[1].toIntOrNull()
                val newStart = header.groupValues[2].toIntOrNull()
                inHunk = oldStart != null && newStart != null
                old = oldStart ?: 0
                new = newStart ?: 0
                DiffLineNumbers(null, null)
            } else if (!inHunk) {
                DiffLineNumbers(null, null)
            } else when {
                line.startsWith("+") && !line.startsWith("+++") ->
                    DiffLineNumbers(null, new++)
                line.startsWith("-") && !line.startsWith("---") ->
                    DiffLineNumbers(old++, null)
                line.startsWith(" ") -> DiffLineNumbers(old++, new++)
                else -> DiffLineNumbers(null, null)
            }
        }
    }

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
