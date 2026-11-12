package com.meowmba.shared.memory

/**
 * Pure text-normalization helpers shared by memory, wake-phrase matching,
 * and speaker naming. Ported from `core/audio/WakePhraseMatcher.normalize`
 * and `core/audio/EnrollPhraseParser.displayName` (the only parts those
 * Android files contribute to memory logic).
 */
object MemoryText {

    /** Lowercase, strip punctuation except apostrophes, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Title-case a raw speaker name ("  aMy   lee " -> "Amy Lee"). */
    fun displayName(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }
}
