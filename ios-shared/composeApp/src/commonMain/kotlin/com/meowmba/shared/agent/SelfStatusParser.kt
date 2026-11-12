package com.meowmba.shared.agent

/**
 * Detects questions whose answers come from runtime state rather than model
 * introspection. The model cannot reliably know where or how it is running.
 */
object SelfStatusParser {
    enum class Question {
        MODEL,
        WHERE,
    }

    private val modelPhrases = listOf(
        "what model are you", "which model are you", "what model is this",
        "what model are you using", "which model are you using",
        "what model are you running", "which model are you running",
        "what model is running", "what llm are you", "which llm are you",
        "what ai are you", "what are you built on", "what's your model",
        "whats your model", "what is your model", "what's your brain",
        "whats your brain", "what is your brain", "which model answered",
        "what model answered", "what model just answered",
    )

    private val wherePhrases = listOf(
        "are you running locally", "are you local or cloud", "are you offline",
        "where are you running", "what are you running on",
        "are you running on my phone", "are you on device", "are you on-device",
        "are you using the cloud", "are you using my server",
    )

    fun parse(raw: String): Question? {
        val text = raw.lowercase()
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null
        if (modelPhrases.any(text::contains)) return Question.MODEL
        if (wherePhrases.any(text::contains)) return Question.WHERE
        return null
    }
}