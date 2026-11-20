package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Test

class UnifiedDiffTest {
    @Test fun countsRealChangesAndIgnoresFileMarkers() {
        val diff = """
diff --git a/readme.txt b/readme.txt
index 1111111..2222222 100644
--- a/readme.txt
+++ b/readme.txt
@@ -1,2 +1,3 @@
 unchanged
-old
+new
+extra
diff --git a/app.kt b/app.kt
new file mode 100644
--- /dev/null
+++ b/app.kt
@@ -0,0 +1 @@
+fun ready() = true
""".trimIndent()
        val sections = UnifiedDiff.parse(diff)
        assertEquals(2, sections.size)
        assertEquals("readme.txt", sections[0].path)
        assertEquals(2, sections[0].additions)
        assertEquals(1, sections[0].deletions)
        assertEquals("app.kt", sections[1].path)
        assertEquals(1, sections[1].additions)
        assertEquals(0, sections[1].deletions)
    }

    @Test fun emptyDiffHasNoPhantomFiles() {
        assertTrue(UnifiedDiff.parse("").isEmpty())
        assertTrue(UnifiedDiff.parse("  \n").isEmpty())
    }

    @Test fun capturesBinaryAndUnstructuredDiff() {
        val binary = UnifiedDiff.parse(
            "diff --git a/img.png b/img.png\nBinary files a/img.png and b/img.png differ")
        assertTrue(binary.single().binary)
        val loose = UnifiedDiff.parse("@@ -1 +1 @@\n-old\n+new")
        assertEquals("当前变更", loose.single().path)
        assertEquals(1, loose.single().deletions)
        assertEquals(1, loose.single().additions)
    }

    @Test fun handlesLinesWithPlusAndMinusInsideContext() {
        val context = UnifiedDiff.parse(
            "diff --git a/x b/x\n--- a/x\n+++ b/x\n context + and -\n+new")
        assertEquals(1, context.single().additions)
        assertEquals(0, context.single().deletions)
    }
}
