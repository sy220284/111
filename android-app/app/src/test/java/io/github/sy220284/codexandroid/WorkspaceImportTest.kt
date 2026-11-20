package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceImportTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                files.forEach { (name, body) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(body.toByteArray())
                    archive.closeEntry()
                }
            }
            output.toByteArray()
        }

    @Test fun zipExtractsIntoIndependentProjectDirectory() {
        val root = temp.newFolder("projects")
        val input = ByteArrayInputStream(zip(
            "src/Main.kt" to "fun main() = Unit",
            "README.md" to "Project"
        ))
        val result = WorkspaceImport.importZip(root, root, "demo.zip", input)
        assertTrue(result.startsWith("已导入工程：demo"))
        assertEquals("fun main() = Unit", File(root, "demo/src/Main.kt").readText())
        assertEquals("Project", File(root, "demo/README.md").readText())
    }

    @Test fun documentImportNeverOverwritesAnExistingSourceFile() {
        val root = temp.newFolder("projects")
        val existing = File(root, "README.md").apply { writeText("Original") }
        try {
            WorkspaceImport.importFile(root, root, "README.md",
                ByteArrayInputStream("Overwrite".toByteArray()))
            fail("Import must reject a filename collision")
        } catch (_: IllegalArgumentException) {}
        assertEquals("Original", existing.readText())
    }

    @Test fun zipTraversalFailsAndPartialProjectIsRemoved() {
        val root = temp.newFolder("projects")
        val input = ByteArrayInputStream(zip("src/Main.kt" to "one", "../escape.txt" to "unsafe"))
        try {
            WorkspaceImport.importZip(root, root, "blocked.zip", input)
            fail("Unsafe ZIP path must be rejected")
        } catch (_: IllegalArgumentException) {}
        assertFalse(File(root, "blocked").exists())
        assertFalse(File(root, "escape.txt").exists())
        assertTrue(root.listFiles()?.none { it.name.startsWith(".codex-import-") } == true)
    }

    @Test fun importTargetsSelectedNestedFolder() {
        val root = temp.newFolder("projects")
        val nested = WorkspaceFileActions.createFolder(root, root, "workspace")
        WorkspaceImport.importFile(root, nested, "note.txt", ByteArrayInputStream("Hello".toByteArray()))
        assertEquals("Hello", File(nested, "note.txt").readText())
        assertFalse(File(root, "note.txt").exists())
    }

    @Test fun reservedStagingNamesAreNotValidUserProjectNames() {
        val root = temp.newFolder("projects")
        try {
            WorkspaceFileActions.createFolder(root, root, ".codex-import-sample")
            fail("Reserved staging names must be rejected")
        } catch (_: IllegalArgumentException) {}
    }
}
