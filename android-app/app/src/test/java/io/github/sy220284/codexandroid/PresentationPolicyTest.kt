package io.github.sy220284.codexandroid

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationPolicyTest {
    @Test fun nullAndPrivateRuntimePathsNeverBecomePageLabels() {
        assertEquals("未命名会话", PresentationPolicy.threadTitle("null", null))
        assertEquals("修复构建", PresentationPolicy.threadTitle("null", "修复构建"))
        assertEquals("本机工作区", PresentationPolicy.workspaceName("/data/user/0/io.github.sy220284.codexandroid/files/projects"))
        assertEquals("本机工作区", PresentationPolicy.workspaceName("null"))
        assertEquals("my-project", PresentationPolicy.workspaceName("/work/my-project"))
        assertEquals("未命名技能", PresentationPolicy.skillName("null"))
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
        assertEquals("可在任务对话中使用，按需启用或停用",
            PresentationPolicy.readableSkillSummary("Create editable Canva instagram posts"))
    }
}
