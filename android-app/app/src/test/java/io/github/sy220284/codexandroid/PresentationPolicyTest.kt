package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class PresentationPolicyTest {
    @Test fun nullAndPrivateRuntimePathsNeverBecomePageLabels() {
        assertEquals("未命名会话", PresentationPolicy.threadTitle("null", null))
        assertEquals("修复构建", PresentationPolicy.threadTitle("null", "修复构建"))
        assertEquals("本机工作区", PresentationPolicy.workspaceName("/data/user/0/io.github.sy220284.codexandroid/files/projects"))
        assertEquals("本机工作区", PresentationPolicy.workspaceName("null"))
        assertEquals("my-project", PresentationPolicy.workspaceName("/work/my-project"))
        assertEquals("未命名技能", PresentationPolicy.skillName("null"))
        assertEquals("未命名服务商", PresentationPolicy.providerName(null))
        assertEquals("未命名服务商", PresentationPolicy.providerName("null"))
        assertEquals("服务商一", PresentationPolicy.providerName("服务商一"))
        assertEquals("Canva instagram post", PresentationPolicy.skillName("canva:canva-instagram-post"))
    }

    @Test fun protocolValuesHaveReadableChinesePresentation() {
        assertEquals("只读", PresentationPolicy.sandboxMode("read-only"))
        assertEquals("可编辑工作区", PresentationPolicy.sandboxMode("workspace-write"))
        assertEquals("完全访问（高风险）", PresentationPolicy.permissionProfile(":danger-full-access"))
        assertEquals("按需请求批准", PresentationPolicy.approvalMode("on-request"))
        assertEquals("Codex 回复", PresentationPolicy.timelineKind("agentMessage"))
        assertEquals("你的指令", PresentationPolicy.timelineKind("userMessage"))
        assertEquals("任务进展", PresentationPolicy.timelineKind("serverInternalEvent"))
        assertEquals("", PresentationPolicy.timelineStatus("internal_code"))
        assertEquals("执行中", PresentationPolicy.timelineStatus("in_progress"))
        assertEquals("权限申请时", PresentationPolicy.hookEvent("permissionRequest"))
        assertEquals("工具运行后", PresentationPolicy.hookEvent("postToolUse"))
        assertEquals("其他触发事件", PresentationPolicy.hookEvent("unknownInternalEvent"))
        assertEquals("纯文本", PresentationPolicy.resourceFormat("text/plain"))
        assertEquals("结构化数据", PresentationPolicy.resourceFormat("application/json"))

        assertEquals("已连接", PresentationPolicy.serviceState("connected"))
        assertEquals("连接失败", PresentationPolicy.serviceState("failed"))
        assertEquals("需要授权", PresentationPolicy.authorizationState("required"))
        assertEquals("可在任务对话中使用，按需启用或停用",
            PresentationPolicy.readableSkillSummary("Create editable Canva instagram posts"))
    }

    @Test fun permissionRequestsExposePreciseReadableScope() {
        val permissions = JSONObject()
            .put("network", JSONObject().put("enabled", true))
            .put("fileSystem", JSONObject()
                .put("read", JSONArray().put("/data/user/0/app/files/projects"))
                .put("entries", JSONArray().put(JSONObject()
                    .put("access", "write")
                    .put("path", JSONObject().put("type", "path").put("path", "/work/story")))))
        val summary = PresentationPolicy.permissionDetails(permissions)
        assertTrue(summary.contains("允许访问网络"))
        assertTrue(summary.contains("读取：本机工作区"))
        assertTrue(summary.contains("修改：story"))
        val rootAccess = JSONObject().put("fileSystem", JSONObject()
            .put("entries", JSONArray().put(JSONObject()
                .put("access", "write")
                .put("path", JSONObject().put("type", "special")
                    .put("value", JSONObject().put("kind", "root"))))))
        assertTrue(PresentationPolicy.permissionDetails(rootAccess)
            .contains("修改：整个文件系统"))

        assertEquals("内核没有提供可核对的授权范围",
            PresentationPolicy.permissionDetails(null).single())
    }

    @Test fun duplicateBackendErrorsAreMergedWithinOnePage() {
        val errors = mapOf(
            "apps" to "Codex 内核尚未连接",
            "appInstalled" to "Codex 内核尚未连接",
            "mcp" to "连接已超时"
        )
        assertEquals(
            listOf("Codex 内核尚未连接", "连接已超时"),
            PresentationPolicy.visibleErrors(errors, listOf("apps", "appInstalled", "mcp"))
        )
        assertEquals(emptyList<String>(), PresentationPolicy.visibleErrors(errors, listOf("models")))
    }

    @Test fun readableErrorKeepsMeaningAndHidesInternalIdentifiers() {
        val source = "访问失败：/data/user/0/io.github.sy220284.codexandroid/files/projects"
        val shown = PresentationPolicy.readableError(source)
        assertTrue(shown.startsWith("访问失败"))
        assertTrue(shown.contains("应用私有目录"))
        assertTrue(!shown.contains("/data/user/"))
        assertTrue(!PresentationPolicy.readableError(
            "01a1fdcb-d65d-76d2-8abc-123456789abc").contains("01a1fdcb"))
    }

    @Test fun evidenceSummarizesRealOutputWithoutShowingProtocolFields() {
        val sections = PresentationPolicy.evidenceSections(
            """{"type":"commandExecution","status":"completed","exitCode":0,"stdout":"done","threadId":"01a1fdcb-d65d-76d2-8abc-123456789abc"}"""
        )
        assertTrue(sections.contains("执行状态" to "已完成"))
        assertTrue(sections.contains("执行结果" to "执行成功"))
        assertTrue(sections.contains("执行输出" to "done"))
        assertTrue(sections.none { (label, value) ->
            "threadId" in label || "exitCode" in label || "stdout" in label ||
                "01a1fdcb" in value
        })

        val withPath = PresentationPolicy.evidenceSections(
            """{"command":"ls /data/user/0/app/files/projects","stderr":"failed","exitCode":1}"""
        )
        assertTrue(withPath.any { it.first == "执行命令" &&
            it.second.contains("应用私有目录") && !it.second.contains("/data/user/") })
        assertTrue(withPath.contains("执行结果" to "执行结束，返回码 1"))
        assertTrue(withPath.contains("错误输出" to "failed"))

        assertEquals(listOf("执行内容" to "terminal output"),
            PresentationPolicy.evidenceSections("terminal output"))
        assertEquals(listOf("执行详情" to "记录了 2 项执行结果"),
            PresentationPolicy.evidenceSections("""[{"id":1},{"id":2}]"""))
    }

}
