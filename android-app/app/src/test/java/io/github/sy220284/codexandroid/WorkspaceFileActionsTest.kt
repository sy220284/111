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

    @Test fun staleCreationNameReportsFreshSuggestionWithoutOverwritingOrRenaming() {
        val root = files.newFolder("creation-conflict")
        val original = WorkspaceFileActions.createTextFile(root, root, "新建文件.txt")
        original.writeText("existing")
        WorkspaceFileActions.createTextFile(root, root, "新建文件 2.txt")
        try {
            WorkspaceFileActions.createTextFile(root, root, "新建文件.txt")
            fail("An already-confirmed name must never be changed silently")
        } catch (conflict: WorkspaceNameConflict) {
            assertEquals("新建文件.txt", conflict.requestedName)
            assertEquals("新建文件 3.txt", conflict.suggestedName)
        }
        assertEquals("existing", original.readText())
        assertFalse(File(root, "新建文件 3.txt").exists())
        WorkspaceFileActions.createFolder(root, root, "新建文件夹")
        try {
            WorkspaceFileActions.createFolder(root, root, "新建文件夹")
            fail("Existing folders must not be replaced")
        } catch (conflict: WorkspaceNameConflict) {
            assertEquals("新建文件夹 2", conflict.suggestedName)
        }
        assertFalse(File(root, "新建文件夹 2").exists())
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

    @Test fun copyingFilesAndFoldersKeepsOriginalAndCreatesIndependentCopy() {
        val root = files.newFolder("projects")
        val folder = WorkspaceFileActions.createFolder(root, root, "demo")
        val nested = WorkspaceFileActions.createFolder(root, folder, "docs")
        val file = WorkspaceFileActions.createTextFile(root, nested, "README.md")
        WorkspaceFileActions.saveText(root, file, "原始内容")
        val duplicated = WorkspaceFileActions.copy(root, folder, root, "demo-copy")
        assertEquals("原始内容", File(duplicated, "docs/README.md").readText())
        val duplicateFile = WorkspaceFileActions.copy(root, file, nested, "README-copy.md")
        WorkspaceFileActions.saveText(root, duplicateFile, "独立副本")
        assertEquals("原始内容", file.readText())
        assertEquals("独立副本", duplicateFile.readText())
    }

    @Test fun copyingNeverOverwritesExistingTargetsOrTraversesLinks() {
        val root = files.newFolder("projects")
        val original = WorkspaceFileActions.createTextFile(root, root, "data.txt")
        WorkspaceFileActions.saveText(root, original, "保留原始内容")
        val existing = WorkspaceFileActions.createTextFile(root, root, "protected.txt")
        WorkspaceFileActions.saveText(root, existing, "不允许覆盖")
        try {
            WorkspaceFileActions.copy(root, original, root, "protected.txt")
            fail("复制不能覆盖已有文件")
        } catch (_: IllegalArgumentException) {}
        assertEquals("不允许覆盖", existing.readText())
        val outside = files.newFolder("outside")
        File(outside, "secret.txt").writeText("私有")
        val folder = WorkspaceFileActions.createFolder(root, root, "links")
        Files.createSymbolicLink(File(folder, "secret").toPath(),
            File(outside, "secret.txt").toPath())
        try {
            WorkspaceFileActions.copy(root, folder, root, "links-copy")
            fail("来源中的符号链接必须被拒绝")
        } catch (_: IllegalArgumentException) {}
        assertFalse(File(root, "links-copy").exists())
        assertEquals("私有", File(outside, "secret.txt").readText())
        assertEquals("保留原始内容", original.readText())
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

    @Test fun editableTextRejectsBinaryAndPreservesConcurrentChanges() {
        val root = files.newFolder("edit-projects")
        val text = File(root, "notes.txt").apply { writeText("原始") }
        val baseline = WorkspaceFileActions.readEditableText(root, text)
        WorkspaceFileActions.saveEditableText(root, text, baseline, "修改")
        assertEquals("修改", text.readText())
        text.writeText("外部写入")
        try {
            WorkspaceFileActions.saveEditableText(root, text, "修改", "草稿")
            fail("外部修改必须保留")
        } catch (_: IllegalArgumentException) {}
        assertEquals("外部写入", text.readText())
        val binary = File(root, "binary.dat").apply { writeBytes(byteArrayOf(0, 1, 2)) }
        try {
            WorkspaceFileActions.readEditableText(root, binary)
            fail("二进制文件不可进入文本编辑器")
        } catch (_: IllegalArgumentException) {}
    }
}
