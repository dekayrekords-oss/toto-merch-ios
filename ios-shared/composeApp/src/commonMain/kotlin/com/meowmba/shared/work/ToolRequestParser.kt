package com.meowmba.shared.work

/**
 * "Make a tool" — a user-made automation that shows up on the Tools page.
 * Example: automatic music player at 7am.
 */
data class ToolStart(
    val name: String,
    /** "HH:mm" daily, or null if they didn't name a time. */
    val timeOfDay: String?,
    /** speak | play_music | launch_app */
    val action: String,
    val launchTarget: String,
    val message: String,
)

/**
 * Ported from `core/work/ToolRequestParser.kt`. Pure Kotlin; the only change is
 * [hhmm], which used the JVM-only `String.format`.
 */
object ToolRequestParser {

    fun parse(normalized: String): ToolStart? {
        val t = stripLead(normalized.trim())
        if (t.isEmpty() || !looksLikeTool(t)) return null
        val time = parseTime(t)
        val action = detectAction(t)
        val target = when (action) {
            "play_music" -> musicQuery(t)
            "launch_app" -> appName(t)
            else -> ""
        }
        val name = extractName(t, action, time)
        val message = when (action) {
            "speak" -> reminderLine(t, name)
            else -> ""
        }
        return ToolStart(
            name = name,
            timeOfDay = time,
            action = action,
            launchTarget = target,
            message = message,
        )
    }

    private fun looksLikeTool(t: String): Boolean {
        if (t.contains("toolbox") || t.contains("toolbar") || t.contains("toolkit")) return false
        val hasToolWord = Regex("\\btools?\\b").containsMatchIn(t)
        val make = t.contains("make") || t.contains("create") || t.contains("add") ||
            t.contains("set up") || t.contains("setup") || t.contains("build")
        if (hasToolWord && make) return true
        val automatic = t.contains("automatic") || t.contains("auto ")
        return automatic && make && parseTime(t) != null
    }

    private fun detectAction(t: String): String = when {
        t.contains("music") || t.contains("spotify") || t.contains("song") ||
            t.contains("playlist") || t.contains("youtube music") -> "play_music"
        Regex("\\b(?:open|launch)\\b").containsMatchIn(t) && appName(t).isNotBlank() ->
            "launch_app"
        else -> "speak"
    }

    private fun musicQuery(t: String): String {
        val on = Regex("\\b(?:play|playing)\\s+(.+?)(?:\\s+at\\s+|$)").find(t)?.groupValues?.getOrNull(1)
            ?.trim().orEmpty()
        if (on.isNotBlank() && on != "music" && !on.contains("music player")) return on
        return "music"
    }

    private fun appName(t: String): String {
        val m = Regex("\\b(?:open|launch)\\s+([a-z0-9 ]+?)(?:\\s+at\\s+|$)").find(t)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .split(Regex("\\b(?:every|each|daily)\\b")).first().trim()
    }

    private fun extractName(t: String, action: String, time: String?): String {
        for (marker in listOf("called ", "named ")) {
            val idx = t.indexOf(marker)
            if (idx >= 0) {
                val raw = t.substring(idx + marker.length).trim()
                    .split(Regex("\\b(?:at|that|to|please)\\b")).firstOrNull()?.trim().orEmpty()
                if (raw.isNotBlank()) return titleCase(raw.take(40))
            }
        }
        val base = when (action) {
            "play_music" -> "Music player"
            "launch_app" -> titleCase(appName(t).ifBlank { "App launch" })
            else -> "Reminder"
        }
        return if (time != null) "$base $time" else base
    }

    private fun reminderLine(t: String, name: String): String {
        val that = Regex("\\b(?:that|to)\\s+(.+?)(?:\\s+at\\s+|$)").find(t)?.groupValues?.getOrNull(1)
            ?.trim().orEmpty()
        if (that.isNotBlank() && that.split(' ').size <= 12) {
            return titleCase(that).let { "Time for $it." }
        }
        return "Time for $name."
    }

    internal fun parseTime(t: String): String? {
        Regex("\\bmidnight\\b").find(t)?.let { return "00:00" }
        Regex("\\bnoon\\b").find(t)?.let { return "12:00" }
        val evening = t.contains("evening") || t.contains("tonight") || t.contains("afternoon")
        val morning = t.contains("morning")
        WORD_HOURS.forEach { (word, hour) ->
            if (Regex("\\b$word\\b").containsMatchIn(t)) {
                return hhmm(adjust(hour, null, morning, evening))
            }
        }
        val m = Regex("""\b(?:at |@ )?(\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?\b""")
            .find(t) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val mer = m.groupValues[3].lowercase().replace(".", "")
        if (hour !in 0..23 || minute !in 0..59) return null
        val h24 = adjust(hour, mer.ifBlank { null }, morning, evening)
        return hhmm(h24, minute)
    }

    private fun adjust(hour: Int, mer: String?, morning: Boolean, evening: Boolean): Int {
        var h = hour
        when {
            mer == "pm" && h in 1..11 -> h += 12
            mer == "am" && h == 12 -> h = 0
            mer == "pm" && h == 12 -> h = 12
            mer == null && evening && h in 1..11 -> h += 12
            mer == null && morning && h == 12 -> h = 0
        }
        return h.coerceIn(0, 23)
    }

    /**
     * `"%02d:%02d".format(hour, minute)` in the original. `String.format` is a
     * JVM-only extension, so this is spelled with [padStart]. Identical for the
     * inputs that reach it: [hour] is already `coerceIn(0, 23)` and [minute] has
     * been range-checked, so both are non-negative and at most two digits.
     */
    private fun hhmm(hour: Int, minute: Int = 0): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    private fun titleCase(s: String): String =
        s.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }

    private fun stripLead(raw: String): String {
        var s = raw
        val leads = listOf(
            "hey meow ", "ok meow ", "okay meow ", "meow ",
            "please ", "can you ", "could you ", "would you ", "hey ",
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

    private val WORD_HOURS = listOf(
        "twelve" to 12, "eleven" to 11, "ten" to 10, "nine" to 9,
        "eight" to 8, "seven" to 7, "six" to 6, "five" to 5,
        "four" to 4, "three" to 3, "two" to 2, "one" to 1,
    )
}
