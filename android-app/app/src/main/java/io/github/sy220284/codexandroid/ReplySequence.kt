package io.github.sy220284.codexandroid

/** A bounded, declarative subset of Mermaid sequenceDiagram, preserving order and arrow semantics. */
internal object ReplySequence {
    data class Participant(val id: String, val label: String)
    data class Message(val sender: String, val receiver: String, val arrow: String, val label: String)
    data class Diagram(val participants: List<Participant>, val messages: List<Message>)

    private val declaration = Regex("""^participant\s+([A-Za-z_][A-Za-z0-9_]{0,63})(?:\s+as\s+(.{1,100}))?$""")
    private val message = Regex("""^([A-Za-z_][A-Za-z0-9_]{0,63})\s*(-->>|->>|-->|->)\s*([A-Za-z_][A-Za-z0-9_]{0,63})\s*:\s*(.{1,160})$""")
    private fun validLabel(label: String) = label.isNotBlank() && label.none { it.isISOControl() || it in "<>" }

    fun parse(source: String): RichReplyContent? {
        if (source.length > 4096) return null
        val lines = source.lines().map(String::trim).filter(String::isNotBlank)
        if (lines.size !in 2..29 || lines.first() != "sequenceDiagram") return null
        val participants = linkedMapOf<String, String>()
        val declared = mutableSetOf<String>()
        val messages = mutableListOf<List<String>>()
        for (line in lines.drop(1)) {
            val participant = declaration.matchEntire(line)
            if (participant != null) {
                val id = participant.groupValues[1]
                val label = participant.groupValues[2].ifBlank { id }.trim()
                if (!validLabel(label) || !declared.add(id) || messages.isNotEmpty()) return null
                participants[id] = label
            } else {
                val match = message.matchEntire(line) ?: return null
                val (from, arrow, to, label) = match.destructured
                if (from == to || !validLabel(label)) return null
                participants.putIfAbsent(from, from)
                participants.putIfAbsent(to, to)
                messages += listOf("message", from, to, arrow, label.trim())
            }
            if (participants.size > 8 || messages.size > 20) return null
        }
        if (messages.isEmpty()) return null
        return RichReplyContent("sequence", participants.map { (id, label) -> listOf("participant", id, label) } + messages)
    }

    fun diagram(rows: List<List<String>>): Diagram = Diagram(
        rows.filter { it[0] == "participant" }.map { Participant(it[1], it[2]) },
        rows.filter { it[0] == "message" }.map { Message(it[1], it[2], it[3], it[4]) })
}
