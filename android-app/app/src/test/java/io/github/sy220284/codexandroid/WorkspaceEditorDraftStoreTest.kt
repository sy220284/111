package io.github.sy220284.codexandroid

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*

class WorkspaceEditorDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun retainsIndependentDraftsAndNeverWritesOriginalFiles() {
        val root = temporary.newFolder("projects")
        val a = File(root, "a.txt").apply { writeText("original A") }
        val b = File(root, "b.txt").apply { writeText("original B") }
        val directory = temporary.newFolder("drafts")
        val store = WorkspaceEditorDraftStore(directory)
        store.save(root, a, WorkspaceEditorDraft("original A", "edited A"))
        store.save(root, b, WorkspaceEditorDraft("original B", "edited B"))
        val reopened = WorkspaceEditorDraftStore(directory)
        assertEquals("edited A", reopened.read(root, a)?.edited)
        assertEquals("edited B", reopened.read(root, b)?.edited)
        assertEquals("original A", a.readText())
        a.writeText("external A")
        assertEquals("original A", reopened.read(root, a)?.base)
        reopened.clear(root, a)
        assertNull(reopened.read(root, a))
        assertEquals("external A", a.readText())
        assertEquals("edited B", reopened.read(root, b)?.edited)
    }
    @Test fun rejectsPathsOutsideWorkspace() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFile("outside.txt")
        val store = WorkspaceEditorDraftStore(temporary.newFolder("store"))
        assertThrows(IllegalArgumentException::class.java) {
            store.save(root, outside, WorkspaceEditorDraft("a", "b"))
        }
    }
}
