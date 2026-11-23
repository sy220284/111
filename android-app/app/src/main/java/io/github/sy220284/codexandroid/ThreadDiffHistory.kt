package io.github.sy220284.codexandroid

import org.json.JSONArray

/**
 * Rebuilds a readable diff from the fileChange items persisted in thread/read.
 * A live turn uses turn/diff/updated; re-opening history uses these durable items.
 */
object ThreadDiffHistory {
    fun latest(turns: JSONArray): String {
        for (turnIndex in turns.length() - 1 downTo 0) {
            val items = turns.optJSONObject(turnIndex)?.optJSONArray("items") ?: continue
            val pieces = mutableListOf<String>()
            for (itemIndex in 0 until items.length()) {
                val item = items.optJSONObject(itemIndex) ?: continue
                if (item.optString("type") != "fileChange") continue
                val changes = item.optJSONArray("changes") ?: continue
                for (changeIndex in 0 until changes.length()) {
                    val change = changes.optJSONObject(changeIndex) ?: continue
                    val diff = change.optString("diff")
                    if (diff.isBlank()) continue
                    val trimmed = diff.trimEnd()
                    if (trimmed.startsWith("diff --git ")) {
                        pieces += trimmed
                    } else {
                        val path = change.optString("path").trim()
                        // Preserve file boundaries so the reader shows distinct files.
                        if (path.isNotBlank()) {
                            pieces += "diff --git a/$path b/$path\n$trimmed"
                        } else {
                            pieces += trimmed
                        }
                    }
                }
            }
            if (pieces.isNotEmpty()) return pieces.joinToString("\n")
        }
        return ""
    }
}
