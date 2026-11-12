package com.meowmba.shared.voice

/**
 * Wake-phrase detection (ported from `core/audio/WakePhraseMatcher.kt`,
 * unchanged in behavior). Pure Kotlin.
 *
 * The companion is addressed by one of several names ("Meow", "Memba", …),
 * often after a carrier ("hey Meow", "yo memba"). [match] finds the phrase at
 * a word boundary and returns the remainder of the utterance, which is what
 * the router and brain actually act on.
 */
object WakePhraseMatcher {

    const val DEFAULT_NAME = "Meow"

    data class Match(val phrase: String, val remainder: String?)

    /** Every address the companion answers to, longest first. */
    val phrases: List<String> = listOf(
        "meowmba", "memba", "meow", "meo"
    ).flatMap { name ->
        buildList {
            add(name)
            add("hey $name")
            add("hi $name")
            add("hello $name")
            add("okay $name")
            add("ok $name")
            add("yo $name")
            add("dude $name")
            add("oi $name")
            add("aye $name")
        }
    }.sortedByDescending { it.length }

    /**
     * Try to find a wake phrase in [text]. Returns null when no phrase is
     * present at a word boundary.
     */
    fun match(text: String): Match? {
        val normalized = normalize(text)
        if (normalized.isBlank()) return null

        for (phrase in phrases) {
            val idx = indexOfPhrase(normalized, phrase)
            if (idx >= 0) {
                val remainder = normalized.substring(idx + phrase.length)
                    .trimStart { it == ' ' || it == ',' || it == '.' }
                    .ifBlank { null }
                return Match(phrase, remainder)
            }
        }
        return null
    }

    /** Lowercase, strip punctuation to spaces, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Word-boundary-aware indexOf: not preceded or followed by a letter. */
    private fun indexOfPhrase(text: String, phrase: String): Int {
        var idx = text.indexOf(phrase)
        while (idx >= 0) {
            val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val afterIdx = idx + phrase.length
            val afterOk = afterIdx >= text.length || !text[afterIdx].isLetterOrDigit()
            if (beforeOk && afterOk) return idx
            idx = text.indexOf(phrase, idx + 1)
        }
        return -1
    }
}
