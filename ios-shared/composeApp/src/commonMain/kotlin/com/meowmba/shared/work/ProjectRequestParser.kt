package com.meowmba.shared.work

/**
 * "Let's start a project" — a codebase, photo, or website session
 * you can reopen later from the Projects folder.
 */
enum class ProjectKind(val label: String) {
    CODE("code"),
    PHOTO("photo"),
    WEBSITE("website"),
    OTHER("project"),
}

data class ProjectStart(
    val name: String,
    val kind: ProjectKind,
)

/**
 * Ported from `core/work/ProjectRequestParser.kt`, unchanged. Pure Kotlin — no
 * JVM-only API is used, so nothing had to be substituted.
 */
object ProjectRequestParser {

    fun parse(normalized: String): ProjectStart? {
        val t = stripLead(normalized.trim())
        if (t.isEmpty() || !looksLikeStart(t)) return null
        val kind = detectKind(t)
        return ProjectStart(name = extractName(t, kind), kind = kind)
    }

    private fun looksLikeStart(t: String): Boolean {
        // A substring match on "start" accepted our own spoken "Started X...
        // Projects" confirmation. Hearing that confirmation could create and
        // announce another project indefinitely. Only an explicit, anchored
        // request can allocate a workspace; reports, negations and quoted
        // confirmations are ordinary conversation.
        return START_REQUEST.containsMatchIn(t) || HELP_REQUEST.containsMatchIn(t)
    }

    private fun detectKind(t: String): ProjectKind = when {
        containsWord(t, "website") || t.contains("web site") ||
            t.contains("webpage") || t.contains("web page") || t.contains("web app") ->
            ProjectKind.WEBSITE
        containsWord(t, "photo") || containsWord(t, "photos") ||
            containsWord(t, "picture") || containsWord(t, "pictures") ||
            containsWord(t, "image") || containsWord(t, "images") ->
            ProjectKind.PHOTO
        containsWord(t, "codebase") || containsWord(t, "code") ||
            containsWord(t, "coding") || containsWord(t, "program") ||
            containsWord(t, "software") || t.contains("app project") ->
            ProjectKind.CODE
        else -> ProjectKind.OTHER
    }

    private fun extractName(t: String, kind: ProjectKind): String {
        for (marker in listOf("called ", "named ", "titled ")) {
            val idx = t.indexOf(marker)
            if (idx >= 0) {
                val raw = t.substring(idx + marker.length).trim()
                val name = raw.split(Regex("\\b(?:for|and then|please)\\b")).firstOrNull()?.trim().orEmpty()
                if (name.isNotBlank()) return titleCase(name.take(48))
            }
        }
        for (marker in listOf("project about ", "project for ", "project to ")) {
            val goal = t.substringAfter(marker, "").trim()
            if (goal.isNotBlank()) return titleCase(goal.take(48))
        }
        return when (kind) {
            ProjectKind.WEBSITE -> "Website"
            ProjectKind.PHOTO -> "Photos"
            ProjectKind.CODE -> "Code"
            ProjectKind.OTHER -> "Project"
        }
    }

    private fun containsWord(t: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(t)

    private fun titleCase(s: String): String =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }

    private fun stripLead(raw: String): String {
        var s = raw
        val leads = listOf(
            "hey meow ", "ok meow ", "okay meow ", "meow ",
            "please ", "can you ", "could you ", "would you ", "hey ",
            "let's ", "lets ", "let us ", "i want to ", "i'd like to ", "i would like to ",
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in leads) {
                if (s.startsWith(p)) {
                    s = s.removePrefix(p)
                    changed = true
                }
            }
        }
        return s.trim()
    }

    private const val PROJECT_NOUN = "(?:(?:website|web|photo|code|coding|software|app) )?(?:project|codebase)\\b"
    private val START_REQUEST = Regex("^(?:(?:start|begin|create|make) (?:a |an |the )?(?:new |another |separate )?|new )$PROJECT_NOUN")
    private val HELP_REQUEST = Regex("^(?:help (?:me )?(?:with|plan) a |plan a )$PROJECT_NOUN")
}
