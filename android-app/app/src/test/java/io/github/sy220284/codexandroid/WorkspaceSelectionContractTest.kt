package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceSelectionContractTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun migratesExistingProjectsWithoutOverwritingPhoneFiles() {
        val legacy = files.newFolder("legacy")
        val root = files.newFolder("shared")
        val old = File(legacy, "novel/chapter.txt").apply { parentFile.mkdirs(); writeText("original") }
        val other = File(root, "keep.txt").apply { writeText("keep") }
        WorkspaceSelectionContract.migrate(legacy, root)
        val mapped = WorkspaceSelectionContract.remap(legacy, root, old)
        assertEquals(File(root, "novel/chapter.txt").canonicalFile, mapped)
        assertEquals("original", mapped.readText())
        assertEquals("keep", other.readText())
        WorkspaceSelectionContract.migrate(legacy, root)
        mapped.writeText("phone edit")
        try {
            WorkspaceSelectionContract.migrate(legacy, root)
            fail("must refuse conflicting existing file")
        } catch (_: IllegalArgumentException) {}
        assertEquals("phone edit", mapped.readText())
        assertEquals("original", old.readText())
    }

    @Test fun chooseValidProjectInsideRoot() {
        val root = files.newFolder("projects")
        val project = File(root, "hello-world").apply { mkdirs() }
        assertEquals(project.canonicalFile, WorkspaceSelectionContract.validate(root, project))
        assertEquals(root.canonicalFile, WorkspaceSelectionContract.validate(root, root))
    }

    @Test fun rejectExternalFolderAndTraversals() {
        val root = files.newFolder("projects")
        val outside = files.newFolder("outside")
        for (target in listOf(outside, File(root, "../outside"))) {
            try {
                WorkspaceSelectionContract.validate(root, target)
                fail("External folder must not be selected: $target")
            } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun rejectSymlinkEscapingPrivateProjects() {
        val root = files.newFolder("projects")
        val outside = files.newFolder("outside")
        val link = root.toPath().resolve("linked")
        Files.createSymbolicLink(link, outside.toPath())
        try {
            WorkspaceSelectionContract.validate(root, link.toFile())
            fail("Symlink outside the workspace should be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun deletedSelectionRestoresToRootWithVisibleWarning() {
        val root = files.newFolder("projects")
        val vanished = File(root, "vanished")
        val restored = WorkspaceSelectionContract.restore(root, vanished.absolutePath)
        assertEquals(root.canonicalFile, restored.directory)
        assertTrue(restored.warning?.contains("重新选择") == true)
        assertNull(WorkspaceSelectionContract.restore(root, null).warning)
    }

    @Test fun deletedProjectCannotBeUsedToStartNewThread() {
        val root = files.newFolder("projects")
        val project = File(root, "project").apply { mkdirs() }
        val canonical = WorkspaceSelectionContract.validate(root, project)
        assertTrue(project.delete())
        try {
            WorkspaceSelectionContract.validate(root, canonical)
            fail("Deleted project should not become an execution cwd")
        } catch (_: IllegalArgumentException) {}
    }
}
