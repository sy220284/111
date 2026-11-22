package io.github.sy220284.codexandroid

/** Stable ID and server-sourced evidence for each displayed execution event. */
data class TimelineItem(
    val id: String,
    val kind: String,
    val text: String,
    val status: String = "",
    val evidence: String = ""
)
