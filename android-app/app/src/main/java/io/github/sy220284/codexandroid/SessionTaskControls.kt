package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

@Composable
internal fun SessionTaskControls(state: SidebarUiState, activeCount: Int, modifier: Modifier = Modifier,
    onActiveTasks: () -> Unit = { state.taskFilter = if (state.taskFilter == SessionTaskFilter.Active) SessionTaskFilter.All else SessionTaskFilter.Active }) {
    var open by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CodexTextAction(onClick = onActiveTasks, modifier = Modifier.weight(1f).testTag("sidebar-active-tasks")) {
            Text("进行中 $activeCount")
        }
        CodexTextAction(onClick = { open = true }, modifier = Modifier.testTag("sidebar-sort-filter")) {
            Text(state.taskFilter.label + " · " + state.sort.label, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (open) CodexDetailSheet("会话排序与筛选", { open = false }) {
        Text("排序方式", style = MaterialTheme.typography.titleSmall)
        for (sort in SessionSort.entries) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sort.label, Modifier.weight(1f))
                CodexRadio(state.sort == sort, onClick = { state.sort = sort })
            }
        }
        Text("任务状态", style = MaterialTheme.typography.titleSmall)
        for (filter in SessionTaskFilter.entries) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(filter.label, Modifier.weight(1f))
                CodexRadio(state.taskFilter == filter, onClick = { state.taskFilter = filter })
            }
        }
        CodexPrimaryButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
    }
}
