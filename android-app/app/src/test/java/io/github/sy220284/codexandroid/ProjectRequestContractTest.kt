package io.github.sy220284.codexandroid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRequestContractTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun projectAndThreadCwdMatchCanonicalPrivateFolder() {
        val root = files.newFolder("projects")
        val project = File(root, "my-app").apply { mkdirs() }
        val created = ProjectRequestContract.create(root, project, " My App ", "request-1")
        assertEquals("My App", created.getString("name"))
        assertEquals(project.canonicalPath,
            created.getJSONArray("roots").getJSONObject(0).getString("path"))
        assertEquals("request-1", created.getString("idempotencyKey"))
        val started = ProjectRequestContract.threadStart(project, "project-123", "gpt")
        assertEquals(project.canonicalPath, started.getString("cwd"))
        assertEquals("project-123", started.getString("projectId"))
        assertEquals("gpt", started.getString("model"))
        assertFalse(ProjectRequestContract.threadStart(root, null, "").has("projectId"))
    }

    @Test fun projectImportPreservesCurrentThreadId() {
        val root = files.newFolder("projects")
        val payload = ProjectRequestContract.importCurrentThread(root, root, "My Project",
            "thread-1", "request-2")
        assertEquals("thread-1", payload.getJSONArray("threads").getString(0))
        assertEquals("request-2", payload.getString("idempotencyKey"))
    }

    @Test fun rejectsProjectOutsideCurrentDevice() {
        val root = files.newFolder("projects")
        val external = files.newFolder("external")
        try {
            ProjectRequestContract.create(root, external, "outside")
            fail("External folder must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun writesCorrectUpdateAndOrderParameters() {
        val name = ProjectRequestContract.rename("project-1", " Next ").toString()
        assertTrue(name.contains("\"projectId\":\"project-1\""))
        assertTrue(name.contains("\"name\":\"Next\""))
        assertTrue(ProjectRequestContract.move("project-1", null).isNull("beforeProjectId"))
        assertEquals("project-2", ProjectRequestContract.move("project-1", "project-2")
            .getString("beforeProjectId"))
    }
}
