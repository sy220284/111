package io.github.sy220284.codexandroid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

@Composable
fun SkillsPage(vm: CodexViewModel, navigate: (String) -> Unit = {}) {
    val data by vm.data.collectEntries("skills")
    val engineStatus by vm.bridge.status.collectAsStateWithLifecycle()
    val skillErrors by vm.errors.collectEntries("skills", "skillsWrite")
    val skillsData = data["skills"]
    val groups = remember(skillsData) { records(skillsData) }
    val skills = remember(groups) { groups.flatMap { it.optJSONArray("skills")?.objects().orEmpty() } }
    val readErrors = remember(groups) { groups.sumOf { it.optJSONArray("errors")?.length() ?: 0 } }
    val mutationLoading by vm.loading.collectProjection { it.intersect(setOf("skills", "skillsWrite")) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredSkills = remember(skills, filter) { when (filter) {
        "enabled" -> skills.filter { it.optBoolean("enabled") }
        "available" -> skills.filter { !it.optBoolean("enabled") }
        "errors" -> emptyList()
        else -> skills
    } }
    val visibleSkills = remember(filteredSkills, query) {
        val term = query.trim()
        if (term.isEmpty()) filteredSkills else filteredSkills.filter { skill ->
            val meta = skill.optJSONObject("interface")
            listOf(skill.optString("name"), skill.optString("description"),
                meta?.optString("displayName").orEmpty()).any { it.contains(term, ignoreCase = true) }
        }
    }
    var confirmation by remember { mutableStateOf<Pair<JSONObject, Boolean>?>(null) }
    var selectedSkill by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        vm.ensureCatalog("skills", "skills/list",
            CatalogRequestContract.workspace(vm.workingDirectory))
    }
    ScreenColumn {
        SectionIntro("技能", "从 Codex 原生目录读取技能，按技能路径单独启用或停用。")
        AtlasCatalogStatus(vm, "skills", "技能", onRefresh = {
            vm.refresh("skills", "skills/list", CatalogRequestContract.workspace(vm.workingDirectory))
        }, onDiagnostics = { navigate("diagnostics") })
        if (skills.isNotEmpty()) {
            WorkSection(Modifier.fillMaxWidth(), WorkSectionKind.Status) {
                Text("工作区技能", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(skills.size.toString(), style = MaterialTheme.typography.headlineMedium)
                        Text("目录技能", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(skills.count { it.optBoolean("enabled") }.toString(),
                            style = MaterialTheme.typography.headlineMedium, color = Ink.jade)
                        Text("启用技能", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                    }
                }
            }
            ClipboardQueryAction("使用剪贴板检索技能", query, onQuery = { query = it },
                onClear = { query = "" }, tag = "skills-search")
            if (query.isNotBlank()) Text("${visibleSkills.size} 项匹配", color = Ink.muted,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("skills-match-count"))
        }
        if (skills.isNotEmpty() || readErrors > 0) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "enabled" to "已启用",
                    "available" to "可用未启用", "errors" to "异常").forEach { (id, label) ->
                    CodexFilterChip(selected = filter == id, onClick = { filter = id },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("skills-filter-" + id),
                        label = { Text(label) })
                }
            }
        }
        visibleSkills.forEach { skill ->
            key(skill.optString("path"), skill.optString("name")) {
                val meta = skill.optJSONObject("interface") ?: JSONObject()
                WorkSection(Modifier.fillMaxWidth().testTag("skills-result-row"), WorkSectionKind.Group) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CodexIcon(CodexGlyph.School, null, tint = Ink.copper)
                        Spacer(Modifier.width(12.dp))
                        Row(Modifier.weight(1f).heightIn(min = 56.dp)
                            .clickable { selectedSkill = skill }.testTag("skill-open-detail"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(PresentationPolicy.meaningful(meta.optString("displayName")).ifEmpty {
                                    PresentationPolicy.skillName(skill.optString("name")) },
                                    style = MaterialTheme.typography.titleMedium)
                                Text(if (skill.optBoolean("enabled")) "已启用 · 可在对话中调用" else "已停用",
                                    color = Ink.muted, style = MaterialTheme.typography.labelMedium)
                            }
                            CodexIcon(CodexGlyph.ChevronRight, "查看详情", tint = Ink.muted)
                        }
                        WhiteSwitch(checked = skill.optBoolean("enabled"), onCheckedChange = { enabled ->
                            confirmation = skill to enabled
                        }, enabled = "skillsWrite" !in mutationLoading && "skills" !in mutationLoading)
                    }
                    DividerLine()
                }
            }
        }
        if (query.isNotBlank() && visibleSkills.isEmpty() && filter != "errors" && skillsData != null) {
            CodexEmptyState("没有匹配的技能", "试试其他名称或清除搜索。",
                actionLabel = "清除搜索", onAction = { query = "" })
        }
        if (readErrors > 0) {
            CodexNotice("有 $readErrors 项技能暂未读取成功，可在诊断与日志中查看原因。",
                NoticeTone.Warning)
        } else if (filter == "errors" && data["skills"] != null && "skills" !in mutationLoading && skillErrors["skills"] == null) {
            CodexEmptyState("当前没有技能读取异常", "所有已返回的技能均已成功读取。")
        } else if (query.isBlank() && filter in setOf("enabled", "available") &&
            visibleSkills.isEmpty() && data["skills"] != null && "skills" !in mutationLoading && skillErrors["skills"] == null) {
            CodexEmptyState("没有匹配的技能", "可以切回全部查看工作区里的技能。",
                actionLabel = "查看全部", onAction = { filter = "all" }, artworkRes = R.drawable.ic_empty_no_results)
        }
        ProtocolFeedback(vm, listOf("skillsWrite"), connectionRecoveryShown = true)
        if ("skillsWrite" in mutationLoading) {
            CodexNotice("正在保存技能状态，完成后自动刷新列表。", NoticeTone.Info)
        }
        if (skills.isEmpty() && data["skills"] != null && readErrors == 0 && filter == "all" && "skills" !in mutationLoading && skillErrors["skills"] == null) {
            CodexEmptyState("当前工作区没有可用技能",
                "可以安装技能包并刷新目录，启用后即可在对话中使用。",
                icon = CodexGlyph.School, actionLabel = "重新读取技能",
                onAction = { vm.refresh("skills", "skills/list",
                    CatalogRequestContract.workspace(vm.workingDirectory)) })
        }
    }
    selectedSkill?.let { skill ->
        SkillDetailSheet(skill, onDismiss = { selectedSkill = null })
    }
    confirmation?.let { (skill, enabled) ->
        CodexConfirmDialog(
            title = if (enabled) "启用技能" else "停用技能",
            message = "将修改此技能在 Codex 中的启用状态。",
            detail = "技能：" + PresentationPolicy.skillName(skill.optString("name")),
            confirmLabel = if (enabled) "确认启用" else "确认停用",
            onConfirm = {
                vm.rpc("skillsWrite", "skills/config/write",
                    JSONObject().put("path", skill.optString("path")).put("enabled", enabled),
                    after = { vm.extensions.skillConfigurationChanged() })
                confirmation = null
            },
            onDismiss = { confirmation = null }
        )
    }
}

