package io.github.sy220284.codexandroid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** 技能说明留在二级详情中；列表只承担识别与开关。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillDetailSheet(skill: JSONObject, onDismiss: () -> Unit) {
    val meta = skill.optJSONObject("interface") ?: JSONObject()
    val name = PresentationPolicy.meaningful(meta.optString("displayName"))
        .ifEmpty { PresentationPolicy.skillName(skill.optString("name")) }
    val description = PresentationPolicy.meaningful(skill.optString("description"))
        .ifEmpty { PresentationPolicy.meaningful(meta.optString("shortDescription")) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.recessed, contentColor = Ink.parchment
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            StatusPill(if (skill.optBoolean("enabled")) "已启用" else "已停用",
                skill.optBoolean("enabled"))
            Text("技能说明", style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(description.ifBlank { "该技能暂未提供说明。" },
                    color = Ink.muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("在任务对话中使用已启用的技能。可返回列表调整启用状态。",
                color = Ink.muted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

