package com.meowmba.shared.personality

/**
 * Phase G1 (LOOI-style personality): a pure, unit-testable mood engine.
 * Events push the companion between moods; time pulls it back. Mood then
 * biases idle animations, TTS pitch/rate, and the occasional idle chatter.
 *
 * Ported from `core/personality/MoodEngine.kt` verbatim — no platform
 * imports, no clock reads (callers pass `now`), so it compiles on every
 * target unchanged.
 */
enum class Mood { CONTENT, EXCITED, SLEEPY, CURIOUS, STARTLED, PROUD }

sealed class MoodEvent {
    object WakeGreeting : MoodEvent()
    object Praise : MoodEvent()
    object Shake : MoodEvent()
    object TaskSuccess : MoodEvent()
    object UserSpoke : MoodEvent()
    object Settled : MoodEvent()
}

class MoodEngine(
    private val idleSleepyAfterMs: Long = 30 * 60_000L,
    private val chatterCooldownMs: Long = 60 * 60_000L,
    private val startledSettleMs: Long = 20_000L,
) {
    var mood: Mood = Mood.CONTENT
        private set

    var lastActivityAt: Long = 0L
        private set

    private var lastChatterAt: Long = Long.MIN_VALUE / 2
    private val recentLines = ArrayDeque<String>(12)

    fun onEvent(event: MoodEvent, now: Long): Mood {
        lastActivityAt = now
        mood = when (event) {
            is MoodEvent.WakeGreeting -> Mood.EXCITED
            is MoodEvent.Praise -> Mood.PROUD
            is MoodEvent.Shake -> Mood.STARTLED
            is MoodEvent.TaskSuccess -> Mood.PROUD
            is MoodEvent.UserSpoke -> Mood.CURIOUS
            is MoodEvent.Settled -> Mood.CONTENT
        }
        return mood
    }

    /** Time-based drift: long silence → sleepy; startle fades on its own. */
    fun tick(now: Long): Mood {
        when {
            mood == Mood.STARTLED && now - lastActivityAt >= startledSettleMs -> mood = Mood.CONTENT
            mood != Mood.SLEEPY && lastActivityAt > 0 && now - lastActivityAt >= idleSleepyAfterMs -> mood = Mood.SLEEPY
        }
        return mood
    }

    fun ttsPitch(m: Mood = mood): Float = when (m) {
        Mood.EXCITED -> 1.15f
        Mood.PROUD -> 1.08f
        Mood.STARTLED -> 1.2f
        Mood.CURIOUS -> 1.05f
        Mood.SLEEPY -> 0.9f
        Mood.CONTENT -> 1.0f
    }

    fun ttsRate(m: Mood = mood): Float = when (m) {
        Mood.EXCITED -> 1.1f
        Mood.STARTLED -> 1.15f
        Mood.SLEEPY -> 0.9f
        else -> 1.0f
    }

    /** Weight multiplier for built-in idle animations given the mood. */
    fun idleAnimationWeight(animName: String, m: Mood = mood): Float = when (m) {
        Mood.SLEEPY -> if (animName == "slow blink") 3f else 0.5f
        Mood.EXCITED -> if (animName == "happy bop") 3f else 1f
        Mood.STARTLED -> if (animName == "ear wiggle") 2.5f else 0.8f
        Mood.PROUD -> if (animName == "happy bop") 1.5f else 1f
        Mood.CURIOUS -> if (animName == "ear wiggle") 1.8f else 1f
        Mood.CONTENT -> 1f
    }

    /** Chatter is rate-limited to once per cooldown window. */
    fun mayChatter(now: Long): Boolean = now - lastChatterAt >= chatterCooldownMs

    fun markChattered(now: Long) {
        lastChatterAt = now
    }

    /** Time-aware one-liners. Same seed in the same hour is stable; different seeds remix. */
    fun pickChatter(nowMs: Long, seed: Int, utcOffsetMinutes: Int = 0): String =
        pickGenerated(nowMs, seed, preferFact = false, utcOffsetMinutes)

    /** User asked for a fact — bias the mixer toward trivia, still remix + de-dupe. */
    fun pickFact(nowMs: Long, seed: Int, utcOffsetMinutes: Int = 0): String =
        pickGenerated(nowMs, seed, preferFact = true, utcOffsetMinutes)

    private fun pickGenerated(nowMs: Long, seed: Int, preferFact: Boolean, utcOffsetMinutes: Int): String {
        var line = IdleChatter.line(nowMs, seed, mood, preferFact, utcOffsetMinutes)
        var bump = 1
        while (line in recentLines && bump < 8) {
            line = IdleChatter.line(nowMs, seed + bump * 97, mood, preferFact, utcOffsetMinutes)
            bump++
        }
        recentLines.addLast(line)
        while (recentLines.size > 12) recentLines.removeFirst()
        return line
    }
}
