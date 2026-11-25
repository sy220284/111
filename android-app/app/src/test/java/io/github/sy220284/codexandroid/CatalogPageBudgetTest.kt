package io.github.sy220284.codexandroid

import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogPageBudgetTest {
    @Test fun failsRatherThanReturningIncompleteData() {
        val budget = CatalogPageBudget(2, 3)
        budget.beforeRequest()
        budget.accept(2, "next")
        budget.beforeRequest()
        budget.accept(1, "last")
        assertThrows(IllegalStateException::class.java) { budget.beforeRequest() }
        assertThrows(IllegalStateException::class.java) { budget.accept(1, null) }
        val loop = CatalogPageBudget()
        loop.beforeRequest()
        loop.accept(0, "same")
        loop.beforeRequest()
        assertThrows(IllegalStateException::class.java) { loop.accept(0, "same") }
    }
}
