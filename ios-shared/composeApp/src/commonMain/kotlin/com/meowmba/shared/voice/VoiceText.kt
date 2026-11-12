package com.meowmba.shared.voice

/**
 * Voice-utterance text helpers shared by the router, greeting answers, and
 * the brain. `stripAddress` is ported from `AgentRouter.stripAddress`
 * (the Android `SpeechIntents` equivalent).
 */
object VoiceText {

    /** Every name the companion answers to, longest first. */
    private val WAKE_CARRIERS = "(?:hey|hi|hello|ok|okay|yo|oi|dude|aye)"

    private val WAKE_NAMES = "(?:meowmba|memba|meow|meo)"

    /**
     * Drops a leading address: an optional carrier plus the name
     * ("hey Meowmba, …", "Meow, …"), or a bare carrier ("yo, …").
     *
     * The name is optional in the second alternative but NOT the first, so a
     * command that merely starts with a carrier still loses the carrier while
     * an ordinary sentence is left alone.
     */
    fun stripAddress(text: String): String {
        val withName = Regex(
            "^$WAKE_CARRIERS\\s+$WAKE_NAMES\\b[\\s,]*",
            RegexOption.IGNORE_CASE,
        )
        var out = withName.replace(text, "")
        if (out == text) {
            out = Regex("^$WAKE_NAMES\\b[\\s,]*", RegexOption.IGNORE_CASE).replace(out, "")
        }
        if (out == text) {
            out = Regex("^$WAKE_CARRIERS\\b[\\s,]*", RegexOption.IGNORE_CASE).replace(out, "")
        }
        return out
    }

    /** Title-case a raw speaker name ("  aMy   lee " -> "Amy Lee"). */
    fun displayName(raw: String): String =
        raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase() }
        }
}
