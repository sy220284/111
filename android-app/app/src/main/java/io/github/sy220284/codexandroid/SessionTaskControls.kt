package io.github.sy220284.codexandroid

import org.json.JSONObject

internal enum class SessionSort(val label: String) {
    Recent("最近活动"), Updated("更新时间"), Created("创建时间");
    fun timestamp(row: JSONObject): Long = when (this) {
        Recent -> SessionListPolicy.recency(row)
        Updated -> row.optLong("updatedAt", 0).takeIf { it > 0 } ?: row.optLong("createdAt", 0)
        Created -> row.optLong("createdAt", 0)
    }
}

internal enum class SessionTaskFilter(val label: String) {
    All("全部"), Active("进行中"), Running("执行中"), Waiting("待处理"), Failed("异常");
    fun matches(activity: ThreadActivity): Boolean = when (this) {
        All -> true
        Active -> ThreadActivityPolicy.isActive(activity)
        Running -> activity == ThreadActivity.Running
        Waiting -> activity == ThreadActivity.WaitingApproval || activity == ThreadActivity.WaitingAnswer
        Failed -> activity == ThreadActivity.Failed
    }
}
