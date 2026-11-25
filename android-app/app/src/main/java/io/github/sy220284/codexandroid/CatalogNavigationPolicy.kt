package io.github.sy220284.codexandroid

/** Large lists retain the complete original order; classification only changes the visible subset. */
internal object CatalogNavigationPolicy {
    fun groups(choices: List<CodexChoice>): Map<String, List<CodexChoice>> {
        if (choices.size < 30) return emptyMap()
        val groups = choices.groupBy { choice ->
            choice.group?.takeIf { it.isNotBlank() } ?: run {
                val title = choice.title.trim()
                if ('/' in title && !title.startsWith('/')) title.substringBefore('/')
                else title.firstOrNull()?.let { c ->
                    when {
                        c in 'a'..'z' || c in 'A'..'Z' -> c.uppercaseChar().toString()
                        c.isDigit() -> "数字"
                        else -> "其他"
                    }
                } ?: "其他"
            }
        }
        return groups.takeIf { it.size > 1 }?.toSortedMap().orEmpty()
    }
}
