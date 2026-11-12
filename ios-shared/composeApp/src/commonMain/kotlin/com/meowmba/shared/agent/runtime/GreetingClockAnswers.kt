package com.meowmba.shared.agent.runtime

import com.meowmba.shared.memory.DateText
import com.meowmba.shared.voice.VoiceText

/**
 * Instant spoken replies for "what's up" / "how are you".
 * Ported from `core/agent/runtime/GreetingAnswers.kt` (with
 * SpeechIntents.stripAddress -> [VoiceText.stripAddress] and
 * EnrollPhraseParser.displayName -> [VoiceText.displayName]).
 *
 * These check-ins do not need a model.
 */
object GreetingAnswers {

    private val BARE = setOf(
        "yo", "hey", "hi", "hello", "sup", "wassup",
        "hey buddy", "yo buddy", "hi buddy",
    )

    /** 20M zipformer plus normal spellings of "what's up". */
    private val WHATS_UP = Regex(
        """\b(what'?s|whats|ulat'?s|ulats|oot'?s|oots|ovites|ovite'?s|wot'?s|wats)\s+up\b""",
    )

    private val HOW_ARE = Regex(
        """\b(how'?s it going|hows it going|how are you|how're you|how you doing|how ya doing)\b""",
    )

    /** Any "<word>'s up" reading. The ASR mangles "what's up" differently
     *  every session, so the pinned spellings can't keep up — accept a short
     *  unknown check-in instead. */
    private val ANY_S_UP = Regex("""\b\w+'?s\s+up\b""")

    fun isGreeting(normalized: String): Boolean {
        val t = VoiceText.stripAddress(normalized).trim()
        if (t.isEmpty()) return false
        if (t in BARE) return true
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        // A check-in is SHORT. Without this cap, "what's up with my calendar
        // today" greeted back instead of routing to the weather/calendar path.
        if (words.size > 5) return false
        return WHATS_UP.containsMatchIn(t) || HOW_ARE.containsMatchIn(t) ||
            ANY_S_UP.containsMatchIn(t)
    }

    private val VARIANTS: List<(String?) -> String> = listOf(
        { who -> who?.let { "Hey $it. What's up?" } ?: "Hey. What's up?" },
        { who -> who?.let { "Yo $it. What do you need?" } ?: "Yo. What do you need?" },
        { _ -> "What's happening?" },
        { who -> who?.let { "Hey $it, how's it going?" } ?: "Hey, how's it going?" },
        { who -> who?.let { "Oh hey $it." } ?: "Oh hey." },
    )
    private var pickCount = 0

    fun reply(speakerName: String?): String {
        val who = speakerName?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { VoiceText.displayName(it) }
        // Rotate lines — the fixed "Hey. What's up?" every time read robotic.
        val line = VARIANTS[pickCount++ % VARIANTS.size]
        return line(who)
    }

    /** Tests pin individual variants by index. */
    internal fun resetVariantCounter(value: Int = 0) {
        pickCount = value
    }
}

/**
 * Deterministic answers to "what time is it" / "what day is it".
 * Ported from `core/agent/runtime/ClockAnswers.kt` with the JVM-only
 * SimpleDateFormat/TimeZone replaced by [DateText] (pure civil-calendar math,
 * explicit UTC-offset minutes). These questions are the phone's job, not the
 * model's — no model call in the path at all.
 */
object ClockAnswers {

    /** Phrases that fix the question as asking the time. */
    private val TIME_PATTERNS = listOf(
        "what time is it", "what time is it now", "what time is it right now",
        "what's the time", "whats the time", "what is the time",
        "tell me the time", "current time", "the current time",
    )

    /** Phrases that fix the question as asking the day or date. */
    private val DATE_PATTERNS = listOf(
        "what day is it", "what day is today", "what day is it today",
        "what day is this", "what is today", "what's today", "whats today",
        "what date is it", "what is the date", "what's the date", "whats the date",
        "what is today's date", "what's today's date", "whats today's date",
        "today's date", "todays date",
    )

    /** Short bare forms, only trusted when the utterance is basically just that. */
    private val SHORT_PATTERNS = listOf(
        "what time", "the time", "what day", "what date",
    )

    private const val MAX_SHORT_WORDS = 4

    /** Only these words legitimately follow "in" in a bare clock question ("in right now"). */
    private val IN_FUNCTION_WORDS = setOf("it", "now", "right")

    /**
     * "what time is it IN NEW YORK" asks about another timezone — the device
     * clock cannot answer that, so it falls through to the model instead.
     */
    private fun mentionsOtherTimezone(t: String): Boolean {
        val inIdx = t.indexOf(" in ")
        if (inIdx < 0) return false
        val after = t.substring(inIdx + 4).trim()
        val firstWord = after.substringBefore(' ').trim()
        return firstWord.isNotEmpty() && firstWord !in IN_FUNCTION_WORDS
    }

    fun isClockQuestion(normalized: String): Boolean {
        val t = normalized.trim()
        if (t.isEmpty() || mentionsOtherTimezone(t)) return false
        if (TIME_PATTERNS.any { t.contains(it) } || DATE_PATTERNS.any { t.contains(it) }) {
            return true
        }
        val words = t.split(Regex("\\s+")).size
        return words <= MAX_SHORT_WORDS && SHORT_PATTERNS.any { t.contains(it) }
    }

    fun isTimeQuestion(normalized: String): Boolean =
        TIME_PATTERNS.any { normalized.contains(it) }

    fun isDateQuestion(normalized: String): Boolean =
        DATE_PATTERNS.any { normalized.contains(it) }

    /** Spoken answer from the given instant, at the given UTC-offset minutes. */
    fun answer(normalized: String, nowMs: Long, utcOffsetMinutes: Int = 0): String =
        if (isTimeQuestion(normalized) && !isDateQuestion(normalized)) {
            "It's " + DateText.timeLine(nowMs, utcOffsetMinutes) + "."
        } else {
            "Today is " + DateText.weekdayMonthDay(nowMs, utcOffsetMinutes) + "."
        }
}
