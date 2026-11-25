package io.github.sy220284.codexandroid

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Export real Kotlin builder results for independent Rust JSON Schema acceptance.
 * No hand-written JSON payload may masquerade as a verified Android request.
 */
class ProtocolWireFixtureExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun exportRealRequestBuildersForRustSchemaValidation() {
        val destination = System.getProperty("codex.protocolFixturesOutput")
            ?.takeIf { it.isNotBlank() }
            ?: error("测试必须显式指定 codex.protocolFixturesOutput")
        val root = temp.newFolder("workspace")
        val project = File(root, "sample-project").apply { mkdirs() }
        val models = listOf(
            JSONObject().put("id", "gpt-6").put("isDefault", true)
                .put("defaultReasoningEffort", "high")
        )
        val records = JSONArray()
        val unique = mutableSetOf<String>()
        fun add(method: String, caseName: String, params: JSONObject) {
            assertTrue("重复的线缆请求样例: $method / $caseName",
                unique.add("$method:$caseName"))
            records.put(JSONObject()
                .put("method", method)
                .put("case", caseName)
                .put("params", params))
        }

        add("thread/start", "local-workspace",
            ProjectRequestContract.threadStart(project, null, ""))
        add("thread/start", "experimental-project-association",
            ProjectRequestContract.threadStart(project, "project-123", "gpt-6"))
        add("turn/start", "default-mode",
            TurnRequestContract.build("thread-1", "检查工程", "", "default", models))
        add("turn/start", "selected-model",
            TurnRequestContract.build("thread-1", "修复编译", "gpt-6", "default", models))
        add("turn/start", "experimental-plan-mode",
            TurnRequestContract.build("thread-1", "制定执行计划", "", "plan", models))
        add("turn/start", "local-image",
            TurnRequestContract.build("thread-1", "分析图片", "", "default", models,
                listOf("/data/user/0/cache/codex-turn-images/example.png")))
        add("turn/steer", "in-flight-instruction",
            TurnRequestContract.steer("thread-1", "turn-1", "继续修复"))
        add("thread/list", "all-sections",
            ThreadSectionContract.threadList(false, "", null, ""))
        add("thread/list", "archived-paginated",
            ThreadSectionContract.threadList(true, "登录", "cursor-1",
                ThreadSectionContract.UNGROUPED))
        add("command/exec", "stdout-stderr",
            TerminalSessionContract.execute("pwd", project.canonicalPath, "proc-1"))
        add("command/exec", "interactive-pty",
            TerminalSessionContract.execute("sh", project.canonicalPath, "proc-2", tty = true))
        add("command/exec/write", "interactive-input",
            TerminalSessionContract.write("proc-2", "你好"))
        add("command/exec/resize", "interactive-resize",
            TerminalSessionContract.resize("proc-2", 42, 128))
        add("command/exec/terminate", "interactive-stop",
            TerminalSessionContract.terminate("proc-2"))
        add("project/create", "idempotent-local",
            ProjectRequestContract.create(root, project, "移动工程", "key-1"))
        add("project/import", "attach-existing-thread",
            ProjectRequestContract.importCurrentThread(root, project, "移动工程",
                "thread-1", "key-2"))
        add("project/read", "lookup",
            ProjectRequestContract.read("project-123"))
        add("project/update", "rename",
            ProjectRequestContract.rename("project-123", "新项目名"))
        add("project/move", "reorder",
            ProjectRequestContract.move("project-123", "project-456"))
        add("project/move", "append",
            ProjectRequestContract.move("project-123", null))

        add("modelProvider/model/verify", "confirmed-domestic-model", DomesticProbeContract.params("provider-fixture", "model-fixture", true))

        val output = File(destination)
        assertTrue("无法建立协议样例输出目录", output.parentFile.isDirectory ||
            output.parentFile.mkdirs())
        output.writeText(JSONObject()
            .put("version", 1)
            .put("source", "Kotlin RequestContract builders executed by JUnit")
            .put("fixtures", records)
            .toString(2) + "\n", Charsets.UTF_8)
        assertTrue("协议构造样例应成功写盘", output.isFile && output.length() > 100L)
        assertEquals(21, records.length())
    }
}
