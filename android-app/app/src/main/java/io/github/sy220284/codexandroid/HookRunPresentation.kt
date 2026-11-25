package io.github.sy220284.codexandroid

import org.json.JSONObject

/** The native outcome determines whether a hook blocked execution. Never infer it from policy. */
internal object HookRunPresentation {
    fun project(params: JSONObject): TimelineItem? {
        val run = params.optJSONObject("run") ?: return null
        val id = run.optString("id").takeIf { it.isNotBlank() } ?: return null
        val status = run.optString("status")
        val result = when (status) {
            "running" -> "执行检查中"
            "completed" -> "执行检查完成"
            "failed" -> "执行检查失败"
            "blocked" -> "执行检查已阻断操作"
            "stopped" -> "执行检查已停止"
            else -> return null
        }
        val event = PresentationPolicy.hookEvent(run.optString("eventName"))
        val entries = run.optJSONArray("entries")
        val reasons = buildList {
            if (status in setOf("failed", "blocked", "stopped")) {
                for (i in 0 until (entries?.length() ?: 0)) {
                    val entry = entries?.optJSONObject(i) ?: continue
                    if (entry.optString("kind") in setOf("error", "stop", "warning", "feedback")) {
                        val value = (entry.opt("text") as? String)?.takeIf { it.isNotBlank() } ?: continue
                        add(PresentationPolicy.readableError(value).take(800))
                    }
                }
            }
        }.distinct().take(3)
        val description = (listOf("$event · $result") + reasons).joinToString("\n")
        val evidence = JSONObject().put("status", status)
        (run.opt("durationMs") as? Number)?.toLong()?.takeIf { it >= 0 }?.let { evidence.put("durationMs", it) }
        if (reasons.isNotEmpty()) evidence.put("error", reasons.joinToString("\n"))
        return TimelineItem("hook:$id", "hookRun", description, status, evidence.toString(),
            (params.opt("turnId") as? String).orEmpty())
    }
}
