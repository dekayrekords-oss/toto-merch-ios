package com.meowmba.shared

import com.meowmba.shared.core.IsoDates
import com.meowmba.shared.personality.IdleChatter
import com.meowmba.shared.personality.Mood
import com.meowmba.shared.personality.MoodEngine
import com.meowmba.shared.personality.MoodEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ported from the Android app's `IdleChatterTest` (same six tests, same
 * assertions). The only adaptation: the original built its fixture with
 * `java.util.Calendar` in the device's default timezone; here the instant is
 * a fixed UTC epoch from [IsoDates] with `utcOffsetMinutes = 0`, so the test
 * means the same thing on every machine and every target — including iOS.
 */
class PersonalityTest {

    /** 2026-08-12 08:15 UTC — a morning hour, explicit offset 0. */
    private fun morning(): Long =
        IsoDates.parse("2026-08-12T08:15:00Z") ?: error("bad fixture")

    @Test
    fun recognisesTheUsualWaysPeopleAskForAFact() {
        listOf(
            "tell me a fact",
            "tell me a fun fact",
            "give me a random fact",
            "can you tell me a fact",
            "hey meow tell me a fact",
            "fun fact",
            "another fact",
            "got any facts",
            "say something interesting",
            "share a fun fact about cats",
        ).forEach {
            assertTrue(IdleChatter.isFactRequest(it), "should route: $it")
        }
    }

    @Test
    fun doesNotStealQuestionsThatOnlyMentionFacts() {
        listOf(
            "what are the facts of my case",
            "remember the fact that I hate cilantro",
            "how are you",
            "what time is it",
        ).forEach {
            assertFalse(IdleChatter.isFactRequest(it), "should NOT route: $it")
        }
    }

    @Test
    fun sameSeedInTheSameHourIsStable() {
        val t = morning()
        val a = IdleChatter.line(t, 42, Mood.CONTENT, preferFact = true)
        val b = IdleChatter.line(t + 60_000L, 42, Mood.CONTENT, preferFact = true)
        assertEquals(a, b)
    }

    @Test
    fun differentSeedsRemixInsteadOfLoopingFourLines() {
        val t = morning()
        val first = IdleChatter.line(t, 0, Mood.CONTENT, preferFact = true)
        assertTrue(first.isNotBlank())
        assertNotEquals(first, IdleChatter.line(t, 1, Mood.CONTENT, preferFact = true))
        val unique = (0 until 80).map {
            IdleChatter.line(t, it * 31, Mood.CONTENT, preferFact = true)
        }.toSet()
        assertTrue(unique.size >= 50, "expected lots of unique facts, got ${unique.size}")
    }

    @Test
    fun moodEngineDeDupesRecentFacts() {
        val e = MoodEngine()
        val t = morning()
        val seen = (0 until 20).map { e.pickFact(t, it) }.toSet()
        assertTrue(seen.size >= 16, "de-dupe should still leave variety, got ${seen.size}")
    }

    @Test
    fun moonNameIsAReadablePhase() {
        val name = IdleChatter.moonName(morning())
        assertTrue(name.contains("moon") || name.contains("quarter"))
    }

    // -- Port-only MoodEngine state tests (the original has no MoodEngineTest) --

    @Test
    fun moodEventsDriveMood() {
        val e = MoodEngine()
        val t = morning()
        assertEquals(Mood.EXCITED, e.onEvent(MoodEvent.WakeGreeting, t))
        assertEquals(Mood.PROUD, e.onEvent(MoodEvent.Praise, t))
        assertEquals(Mood.STARTLED, e.onEvent(MoodEvent.Shake, t))
        assertEquals(Mood.CURIOUS, e.onEvent(MoodEvent.UserSpoke, t))
        assertEquals(Mood.CONTENT, e.onEvent(MoodEvent.Settled, t))
    }

    @Test
    fun longSilenceDriftsToSleepyAndStartleSettles() {
        val e = MoodEngine()
        val t = morning()
        e.onEvent(MoodEvent.Shake, t)
        assertEquals(Mood.CONTENT, e.tick(t + 20_000L))
        e.onEvent(MoodEvent.UserSpoke, t)
        assertEquals(Mood.SLEEPY, e.tick(t + 30 * 60_000L))
    }

    @Test
    fun chatterCooldownGates() {
        val e = MoodEngine(chatterCooldownMs = 60_000L)
        val t = morning()
        assertTrue(e.mayChatter(t))
        e.markChattered(t)
        assertFalse(e.mayChatter(t + 1_000L))
        assertTrue(e.mayChatter(t + 60_000L))
    }

    @Test
    fun sleepyMoodMarksTheLine() {
        val t = morning()
        val line = IdleChatter.line(t, 7, Mood.SLEEPY)
        assertTrue(line.endsWith("*yawn*"), "sleepy tail missing: $line")
    }
}
