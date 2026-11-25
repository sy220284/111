package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class CatalogNavigationPolicyTest {
    @Test fun largeCatalogClassificationPreservesEveryChoiceAndOriginalOrder() {
        val options = (1..40).map { CodexChoice(it.toString(), "Model $it", group = if (it % 2 == 0) "支持图片" else "文字") }
        val groups = CatalogNavigationPolicy.groups(options)
        assertEquals(options.size, groups.values.sumOf { it.size })
        assertEquals(options.filter { it.group == "文字" }, groups["文字"])
        assertEquals(options.map { it.id }.toSet(), groups.values.flatten().map { it.id }.toSet())
        assertTrue(CatalogNavigationPolicy.groups(options.take(10)).isEmpty())
    }
}
