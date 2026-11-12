package com.meowmba.shared.voice

/**
 * Turns a chat reply into something a speech synthesizer reads naturally. Markdown markers,
 * code blocks, raw URLs and emoji are written for eyes, not ears; reading "asterisk asterisk"
 * or a whole code listing aloud is worse than skipping it. The on-screen reply is unchanged.
 */
object SpeechText {
    private val thinking = Regex("<think>[\\s\\S]*?(</think>|$)", RegexOption.IGNORE_CASE)
    private val fence = Regex("```[\\s\\S]*?(```|$)")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)")
    private val url = Regex("https?://\\S+")
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val bullet = Regex("(?m)^\\s*(?:[-*+•]|\\d+[.)])\\s+")
    private val emphasis = Regex("(\\*\\*|__|\\*|_|~~)(?=\\S)(.+?)(?<=\\S)\\1")
    private val table = Regex("(?m)^\\s*\\|?\\s*:?-{3,}.*$")
    // Android SpeechText (176): coordinates are read as "your current location", keycaps dropped.
    private val coordinates = Regex("(?i)(?:latitude\\s*[:=]?\\s*)?([+-]?\\d{1,2}\\.\\d{3,})\\s*°?\\s*[NS]?\\s*(?:[,;/]|\\s+longitude\\s*[:=]?)\\s*([+-]?\\d{1,3}\\.\\d{3,})\\s*°?\\s*[EW]?")
    private val labeledCoordinate = Regex("(?i)\\b(?:latitude|longitude|lat|lon|lng)\\s*[:=]?\\s*[+-]?\\d{1,3}(?:\\.\\d+)?\\s*°?\\s*[NSEW]?\\b")
    private val dms = Regex("[+-]?\\d{1,3}°\\s*\\d{1,2}['′]\\s*\\d{1,2}(?:\\.\\d+)?[\"″]\\s*[NSEW]", RegexOption.IGNORE_CASE)
    private val keycap = Regex("[#*0-9]\uFE0F?\u20E3")

    fun forSpeech(reply: String): String {
        var t = thinking.replace(reply, " ")
        t = fence.replace(t, " (code shown on screen) ")
        t = link.replace(t) { it.groupValues[1] }
        t = url.replace(t, "a link")
        t = inlineCode.replace(t) { it.groupValues[1] }
        t = table.replace(t, " ")
        t = heading.replace(t, "")
        t = bullet.replace(t, "")
        repeat(2) { t = emphasis.replace(t) { it.groupValues[2] } }
        t = t.replace('|', ' ')
        t = keycap.replace(t, "")
        t = dms.replace(t, "your current location")
        t = coordinates.replace(t) { m ->
            val lat = m.groupValues[1].toDoubleOrNull(); val lon = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) "your current location" else m.value
        }
        t = labeledCoordinate.replace(t, "your current location")
        t = stripEmoji(t)
        return t.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }

    /** Drops emoji (U+1F000–1FAFF, U+2600–27BF, variation selector, joiner) by hand: Native regex can't match surrogate pairs. */
    private fun stripEmoji(t: String): String = buildString {
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c.isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()) {
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (t[i + 1].code - 0xDC00)
                if (cp !in 0x1F000..0x1FAFF) { append(c); append(t[i + 1]) }
                i += 2; continue
            }
            if (c.code !in 0x2600..0x27BF && c != '\uFE0F' && c != '\u200D') append(c)
            i++
        }
    }
}
