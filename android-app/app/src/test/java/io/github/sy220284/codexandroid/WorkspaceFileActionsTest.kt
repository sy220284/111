package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileActionsTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun folderCreateRenameAndDeleteStayInProjectsRoot() {
        val root = files.newFolder("projects")
        val project = WorkspaceFileActions.createFolder(root, root, "my-project")
        assertTrue(project.isDirectory)
        val file = WorkspaceFileActions.createTextFile(root, project, "notes.md")
        WorkspaceFileActions.saveText(root, file, "可复用工程")
        assertEquals("可复用工程", file.readText())
        val renamed = WorkspaceFileActions.rename(root, file, "README.md")
        assertFalse(file.exists())
        assertEquals("可复用工程", renamed.readText())
        WorkspaceFileActions.delete(root, project)
        assertFalse(project.exists())
        assertTrue(root.exists())
    }

    @Test fun disallowTraversalAndRootDeletion() {
        val root = files.newFolder("projects")
        for (name in listOf("../escape", "a/b", ".", "..", " ", "a\\b")) {
            try {
                WorkspaceFileActions.child(root, root, name)
                fail("Traversal name must fail: " + name)
            } catch (_: IllegalArgumentException) { }
        }
        try {
            WorkspaceFileActions.delete(root, root)
            fail("Workspace root must never be deleted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun disallowRenamingOntoExistingFileAndKeepOriginal() {
        val root = files.newFolder("projects")
        val a = WorkspaceFileActions.createTextFile(root, root, "one.txt")
        WorkspaceFileActions.createTextFile(root, root, "two.txt")
        WorkspaceFileActions.saveText(root, a, "Original")
        try {
            WorkspaceFileActions.rename(root, a, "two.txt")
            fail("Rename should not replace existing contents")
        } catch (_: IllegalArgumentException) { }
        assertEquals("Original", a.readText())
    }

    @Test fun doNotDeleteThroughLinkPointingOutsideWorkspace() {
        val root = files.newFolder("projects")
        val outside = files.newFolder("private-files")
        val protected = File(outside, "secret.txt").apply { writeText("private") }
        val link = File(root, "external")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        try {
            WorkspaceFileActions.delete(root, link)
            fail("Following an external link must be blocked")
        } catch (_: IllegalArgumentException) { }
        assertEquals("private", protected.readText())
    }
}
