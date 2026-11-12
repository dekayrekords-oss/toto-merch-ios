package com.meowmba.shared.voice

/** Android SpeechInterruptionPolicy.isQuietRequest + NonSpeechSound (build 176). */
object SpeechQuiet {
    fun isQuietRequest(raw: String): Boolean {
        val text = WakePhraseMatcher.normalize(raw).removePrefix("hey ").removePrefix("meow ").removePrefix("please ").removeSuffix(" please")
            .removeSuffix(" meow").trim()
        return text in setOf("be quiet", "stay quiet", "stop talking", "stop speaking", "shush", "shut up", "silence", "quiet")
    }

    private val bracket = Regex("[\\[(*]([^\\[\\]()*]+)[\\])*]")

    /** A transcript that is only "[laughs]" or "(background noise)" is an observation, never a turn. */
    fun isOnlyNonSpeech(raw: String): Boolean {
        // A transcript cut off mid-tag ends with a dangling "(" or "(click": still only tags.
        val text = raw.trim().trimEnd('.', ',', '!', '?').trim()
            .replace(Regex("\\s*[\\[(][^\\[\\]()]{0,40}$"), "").trim()
            .ifEmpty { raw.trim() }
        val parts = bracket.findAll(text).toList()
        if (parts.isEmpty() || bracket.replace(text, "").isNotBlank()) return false
        val labels = parts.map { it.groupValues[1].trim().lowercase() }
        if (labels.any { it.length > 80 || it.none(Char::isLetter) }) return false
        return labels.none { Regex("^(?:open|close|play|pause|stop|turn|read|tell|show|please|can you|could you)\\s+\\S+").containsMatchIn(it) }
    }
}
