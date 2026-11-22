package io.github.sy220284.codexandroid

/** Query identities preserve exact filter boundaries even when strings contain ':' or '|'. */
internal object ThreadLibraryQuery {
    private fun encode(value: String): String = "${value.length}:$value"

    fun key(archived: Boolean, term: String, section: String, cursor: String?): String =
        "library:" + listOf(
            if (archived) "archived" else "active",
            term, section, cursor ?: ""
        ).joinToString("|", transform = ::encode)

    fun fullTextKey(term: String): String = "fullSearch:" + encode(term)
}
