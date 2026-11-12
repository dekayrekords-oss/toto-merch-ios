package com.meowmba.shared.ui.flow

/**
 * How much Meow speaks aloud. Owner (2026-09-23): "it talks a lot, and when it's not necessary" —
 * and "the app is supposed to be proactive more than reactive". Meow always brings things forward on
 * screen and by notification; this only decides when it also speaks. Default IMPORTANT.
 */
enum class SpeechMode(val key: String, val label: String, val detail: String) {
    REPLIES("replies", "When I talk", "Answers out loud only when you spoke to it. Typed questions get typed answers."),
    IMPORTANT("important", "Important", "Speaks up on its own when it matters, while you drive or wear headphones. Typed questions get typed answers."),
    ALWAYS("always", "Always", "Speaks every reply and reminder in full.");

    companion object {
        const val PREF = "speech_mode"
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: IMPORTANT

        /** First two sentences, at most ~240 characters: the rest stays on screen. */
        fun shorten(text: String, maxSentences: Int = 2, maxChars: Int = 240): String {
            val t = text.trim()
            val ends = Regex("(?<=[.!?])\\s+").findAll(t).map { it.range.first }.toList()
            val cut = ends.getOrNull(maxSentences - 1)?.let { t.substring(0, it) } ?: t
            if (cut.length <= maxChars) return cut
            val space = cut.lastIndexOf(' ', maxChars)
            return cut.substring(0, if (space > 80) space else maxChars).trimEnd(',', ';', ':') + "…"
        }
    }
}
