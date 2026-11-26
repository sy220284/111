package io.github.sy220284.codexandroid

import org.json.JSONObject

/** Mirrors the locked AgentMessageDisplay discriminated union. Unknown versions fail closed. */
internal object SafetyReplyPresentation {
    enum class Mode { Normal, Show, Hide, Protection, Cyber, Restricted }
    data class Presentation(val mode: Mode, val helpline: Boolean = false,
        val subject: String? = null, val trustedContactsCheck: Boolean = false,
        val trustedAccess: Boolean? = null) {
        val restricted get() = mode !in setOf(Mode.Normal, Mode.Show)
        val replacement get() = when (mode) {
            Mode.Hide -> "如果你正经历难以承受的困扰，可以联系当地专业支持或信任的人。若有迫在眉睫的危险，请联系当地紧急服务。"
            Mode.Protection -> "此请求触及运行核心的安全限制。可以改为讨论安全背景、风险预防与应对。"
            Mode.Cyber -> "此请求受到网络安全访问限制。可以继续讨论授权范围内的防护、评估与修复。"
            Mode.Restricted -> "此回答受到运行核心的内容展示限制。"
            else -> ""
        }
    }
    fun parse(raw: JSONObject?): Presentation {
        if (raw == null || raw.isNull("moderation")) return Presentation(Mode.Normal)
        val moderation = raw.optJSONObject("moderation") ?: return Presentation(Mode.Restricted)
        val display = moderation.optJSONObject("display")
        if (display == null) return Presentation(if (moderation.opt("moderated") == false &&
            moderation.isNull("display")) Mode.Normal else Mode.Restricted)
        if (display.opt("version") !is Number || display.optInt("version") != 1 ||
            display.optDouble("version") != 1.0) return Presentation(Mode.Restricted)
        return when (display.optString("mode")) {
            "show" -> {
                val contacts = display.optString("trustedContacts")
                val annotation = display.optJSONObject("annotation")
                val subject = annotation?.optString("subject")
                if (contacts !in setOf("check", "skip") || (!display.isNull("annotation") &&
                    (annotation?.optString("kind") != "wellbeingHelpline" || subject !in setOf("answer", "prompt"))))
                    Presentation(Mode.Restricted)
                else Presentation(Mode.Show, annotation != null, subject, contacts == "check")
            }
            "hide" -> if (display.optJSONObject("replacement")?.optString("kind") == "wellbeingPromptHelpline")
                Presentation(Mode.Hide, helpline = true, subject = "prompt") else Presentation(Mode.Restricted)
            "block" -> when (val reason = display.optJSONObject("reason")?.optString("kind")) {
                "protection" -> if (display.getJSONObject("reason").optString("protectionType").isNotBlank())
                    Presentation(Mode.Protection) else Presentation(Mode.Restricted)
                "cyber" -> Presentation(Mode.Cyber,
                    trustedAccess = display.getJSONObject("reason").opt("trustedAccess") as? Boolean)
                else -> Presentation(Mode.Restricted)
            }
            else -> Presentation(Mode.Restricted)
        }
    }
}
