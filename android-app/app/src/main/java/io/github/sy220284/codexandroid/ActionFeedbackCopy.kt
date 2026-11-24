package io.github.sy220284.codexandroid

import org.json.JSONObject

/** User-facing outcomes for real write requests. Protocol names never become UI labels. */
data class ActionFeedbackReceipt(
    val message: String, val isError: Boolean = false, val undo: ThreadUndoOffer? = null
)

object ActionFeedbackCopy {
    private data class Copy(val success: String, val attempt: String)
    private val known = mapOf(
        "thread/name/set" to Copy("会话名称已更新", "重命名会话"),
        "thread/archive" to Copy("会话已归档", "归档会话"),
        "thread/unarchive" to Copy("会话已恢复", "恢复会话"),
        "thread/delete" to Copy("会话已删除", "删除会话"),
        "thread/fork" to Copy("已创建会话分支", "创建会话分支"),
        "thread/revert" to Copy("已完成会话回退", "回退会话"),
        "thread/compact/start" to Copy("上下文整理已开始", "整理上下文"),
        "threadSection/create" to Copy("会话分组已建立", "新建分组"),
        "threadSection/update" to Copy("会话分组已更新", "编辑分组"),
        "threadSection/delete" to Copy("会话分组已删除", "删除分组"),
        "thread/section/move" to Copy("会话已移动到目标分组", "移动会话"),
        "thread/goal/set" to Copy("任务目标已保存", "保存任务目标"),
        "thread/goal/clear" to Copy("任务目标已清除", "清除任务目标"),
        "thread/queue/add" to Copy("已加入后续指令", "添加后续指令"),
        "thread/queue/start" to Copy("已开始执行后续指令", "启动后续指令"),
        "thread/queue/update" to Copy("任务步骤已更新", "更新任务步骤"),
        "thread/queue/delete" to Copy("任务步骤已删除", "删除任务步骤"),
        "thread/queue/reorder" to Copy("任务步骤顺序已更新", "调整任务顺序"),
        "skills/config/write" to Copy("技能启用状态已保存", "调整技能"),
        "plugin/install" to Copy("插件已安装", "安装插件"),
        "plugin/uninstall" to Copy("插件已卸载", "卸载插件"),
        "modelProvider/config/write" to Copy("模型服务商设置已保存", "保存服务商设置"),
        "modelProvider/default/set" to Copy("默认服务商已更新", "切换默认服务商"),
        "config/value/write" to Copy("设置已保存", "保存设置"),
        "config/batchWrite" to Copy("权限及配置已保存", "保存权限及配置"),
        "mcpServer/oauth/login" to Copy("已发起外部服务授权", "发起外部服务授权"),
        "mcpServer/tool/call" to Copy("外部工具已返回结果", "调用外部工具"),
        "environment/add" to Copy("执行环境已添加", "添加执行环境"),
        "account/logout" to Copy("已退出账号", "退出账号"),
        "accountSession/switch" to Copy("已切换账号会话", "切换账号"),
        "review/start" to Copy("代码审查已发起", "发起代码审查"),
        "thread/attachment/add" to Copy("会话附件已添加", "添加会话附件"),
        "thread/attachment/remove" to Copy("会话附件已移除", "移除会话附件"),
        "turn/interrupt" to Copy("已发送任务停止请求", "停止当前任务")
    )

    fun success(method: String, response: JSONObject): ActionFeedbackReceipt {
        if (method == "mcpServer/tool/call" && response.optBoolean("isError")) {
            return ActionFeedbackReceipt("外部工具返回错误，请查看执行结果", isError = true)
        }
        return ActionFeedbackReceipt(known[method]?.success ?: "操作已完成")
    }

    fun failure(method: String, reason: String): ActionFeedbackReceipt {
        val detail = reason.trim().removePrefix(method + "：").removePrefix(method + ":").trim()
        return ActionFeedbackReceipt(
            (known[method]?.attempt ?: "执行操作") + "失败：" +
                if (detail.isBlank()) "请检查连接及操作权限"
                else PresentationPolicy.readableError(detail),
            isError = true
        )
    }
}
