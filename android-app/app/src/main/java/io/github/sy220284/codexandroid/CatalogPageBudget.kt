package io.github.sy220284.codexandroid

/** Limits a complete server-owned cursor traversal without publishing a truncated snapshot. */
internal class CatalogPageBudget(private val maxPages: Int = 1000, private val maxItems: Int = 100_000) {
    init { require(maxPages > 0 && maxItems > 0) }
    private var pages = 0
    private var items = 0
    private val cursors = hashSetOf<String>()

    fun beforeRequest() {
        check(pages < maxPages) { "内核目录分页超过安全上限，请重新连接后刷新" }
        pages++
    }

    fun accept(count: Int, next: String?) {
        require(count >= 0)
        check(count <= maxItems - items) { "内核目录条目超过安全上限，请重新连接后刷新" }
        items += count
        check(next == null || cursors.add(next)) { "内核目录返回重复游标" }
    }
}
