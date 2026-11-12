package com.meowmba.shared.agent.runtime

import com.meowmba.shared.core.PlatformTime

/**
 * Bounded, conversation-scoped working context. History is reference data,
 * never approval. Ported from `core/agent/runtime/TurnContext.kt` with the
 * JVM-only `System::currentTimeMillis` default replaced by [PlatformTime].
 */
class TurnContext(private val now: () -> Long = { PlatformTime.currentMillis() }) {
    private var conversation = Long.MIN_VALUE
    private val turns = ArrayDeque<Pair<String, String>>()
    private var weatherUntil = 0L

    fun scope(id: Long) {
        if (conversation != id) {
            conversation = id
            turns.clear()
            weatherUntil = 0
        }
    }

    fun add(role: String, text: String) {
        val turn = role to text.take(800)
        if (turns.lastOrNull() != turn) turns.addLast(turn)
        while (turns.size > 8) turns.removeFirst()
    }

    fun history(): String? = turns.takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "${it.first}: ${it.second}" }

    fun historyWith(persisted: List<Pair<String, String>>): String? {
        fun canonical(turn: Pair<String, String>) =
            (if (turn.first in setOf("Meow", "Assistant")) "Assistant" else turn.first) to turn.second.take(800)
        val saved = persisted.takeLast(16).map(::canonical)
        val live = turns.map(::canonical)
        // Room may lag behind live speech. Merge at the largest overlapping
        // suffix/prefix; a just-added User turn must not hide restored context.
        val overlap = (minOf(saved.size, live.size) downTo 1).firstOrNull { saved.takeLast(it) == live.take(it) } ?: 0
        val merged = (saved + live.drop(overlap)).takeLast(16)
        return merged.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.first}: ${it.second}" }
    }

    fun awaitWeatherPlace() {
        weatherUntil = now() + 180_000
    }

    fun awaitingWeatherPlace() = weatherUntil > now()

    fun clearWeather() {
        weatherUntil = 0
    }

    companion object {
        fun dependent(text: String): Boolean {
            val n = text.lowercase().trim()
            return Regex("\\b(it|that|those|them|same|instead|there|again)\\b").containsMatchIn(n) ||
                Regex("^(yes|yeah|yep|sure|ok|okay|and|also|what about|how about)\\b").containsMatchIn(n)
        }
    }
}
