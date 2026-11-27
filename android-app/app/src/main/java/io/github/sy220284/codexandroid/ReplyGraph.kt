package io.github.sy220284.codexandroid

/** Deterministic bounded graph parser/layout; no executable Mermaid directives or HTML. */
internal object ReplyGraph {
    data class Node(val name: String, val layer: Int, val column: Int)
    data class Layout(val nodes: List<Node>, val edges: List<List<String>>, val columns: Int, val layers: Int)
    fun layout(edges: List<List<String>>, direction: String = "TD"): Layout {
        val names = edges.flatMap { listOf(it[0], it[2]) }.distinct()
        val adjacency = edges.groupBy { it[0] }
        val destinations = edges.mapTo(hashSetOf()) { it[2] }
        val levels = linkedMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        fun visit(node: String, depth: Int) {
            if (node !in levels) { levels[node] = depth; queue.addLast(node) }
        }
        names.filter { it !in destinations }.forEach { visit(it, 0) }
        for (root in names) {
            if (queue.isEmpty() && root !in levels) visit(root, 0)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                adjacency[node].orEmpty().forEach { visit(it[2], levels.getValue(node) + 1) }
            }
        }
        val nodes = levels.entries.groupBy { it.value }.flatMap { (level, group) ->
            group.mapIndexed { index, entry -> Node(entry.key, level, index) }
        }
        val maxLayer = nodes.maxOfOrNull { it.layer } ?: 0
        val positioned = nodes.map { n -> when (direction) {
            "BT" -> n.copy(layer = maxLayer - n.layer)
            "LR" -> n.copy(layer = n.column, column = n.layer)
            "RL" -> n.copy(layer = n.column, column = maxLayer - n.layer)
            else -> n
        } }
        return Layout(positioned, edges, (positioned.maxOfOrNull { it.column } ?: 0) + 1,
            (positioned.maxOfOrNull { it.layer } ?: 0) + 1)
    }
    private val header = Regex("^(?:flowchart|graph)\\s+(?:TD|TB|BT|LR|RL)\\s*;?$")
    private val node = Regex("""^([A-Za-z_][A-Za-z0-9_]{0,63})(?:\["?([^"\[\]<>]{1,100})"?\]|\("?([^"()<>]{1,100})"?\)|\{"?([^"{}<>]{1,100})"?\})?$""")
    private val edge = Regex("""^(.+?)\s*(?:-->|==>|-\.->)\s*(?:\|([^|<>]{1,100})\|\s*)?(.+)$""")
    fun parseMermaid(source: String): RichReplyContent? {
        if (source.length > 4096) return null
        val lines = source.lines().map { it.trim().removeSuffix(";") }.filter(String::isNotBlank)
        if (lines.size !in 2..25 || !header.matches(lines.first())) return null
        val labels = linkedMapOf<String, String>()
        val edges = mutableListOf<List<String>>()
        fun parseNode(value: String): String? {
            val match = node.matchEntire(value.trim()) ?: return null
            val id = match.groupValues[1]
            val label = match.groupValues.drop(2).firstOrNull(String::isNotBlank)
            if (label != null) {
                if (labels[id] != null && labels[id] != label) return null
                labels[id] = label.trim()
            }
            return id
        }
        for (line in lines.drop(1)) {
            val match = edge.matchEntire(line) ?: return null
            val a = parseNode(match.groupValues[1]) ?: return null
            val b = parseNode(match.groupValues[3]) ?: return null
            if (a == b) return null
            edges += listOf(a, match.groupValues[2].ifBlank { "连接" }, b)
        }
        val names = edges.flatMap { listOf(it[0], it[2]) }.distinct()
        if (names.size > 16) return null
        val displayed = names.associateWith { labels[it] ?: it }
        if (displayed.values.distinct().size != names.size) return null
        return RichReplyContent("graph", edges.map { listOf(displayed.getValue(it[0]), it[1], displayed.getValue(it[2])) }.distinct())
    }
}
