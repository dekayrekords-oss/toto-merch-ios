package com.meowmba.shared

import com.meowmba.shared.memory.ChatMessage
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.memory.Importance
import com.meowmba.shared.memory.LocalHashEmbedder
import com.meowmba.shared.memory.MemoryCommandParser
import com.meowmba.shared.memory.MemoryExporter
import com.meowmba.shared.memory.MemoryItem
import com.meowmba.shared.memory.MemoryText
import com.meowmba.shared.memory.MessageRole
import com.meowmba.shared.memory.PromptBuilder
import com.meowmba.shared.memory.RecallEngine
import com.meowmba.shared.memory.VectorCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryCoreTest {

    @Test
    fun vectorCodecRoundTrips() {
        val vector = floatArrayOf(0.25f, -1.5f, 0f, 3.75f, Float.MIN_VALUE)
        val decoded = VectorCodec.decode(VectorCodec.encode(vector))
        assertTrue(vector.contentEquals(decoded))
    }

    @Test
    fun embedderIsDeterministicAndNormalized() = runTest {
        val embedder = LocalHashEmbedder()
        val a = embedder.getEmbedding("I like green tea")
        val b = embedder.getEmbedding("I like green tea")
        assertTrue(a.contentEquals(b))
        val norm = a.fold(0f) { acc, v -> acc + v * v }
        assertEquals(1f, norm, 1e-4f)
    }

    @Test
    fun similarTextScoresHigherThanUnrelated() = runTest {
        val embedder = LocalHashEmbedder()
        val query = embedder.getEmbedding("green tea")
        val near = embedder.getEmbedding("I like green tea very much")
        val far = embedder.getEmbedding("quarterly tax documents spreadsheet")
        assertTrue(RecallEngine.cosine(query, near) > RecallEngine.cosine(query, far))
    }

    @Test
    fun recallRanksByWeightedSimilarityAndExplains() = runTest {
        val embedder = LocalHashEmbedder()
        val query = embedder.getEmbedding("green tea")
        val memory = MemoryItem(
            id = 1,
            content = "User likes green tea",
            timestamp = 1_700_000_000_000,
            importance = Importance.HIGH,
            embedding = VectorCodec.encode(embedder.getEmbedding("User likes green tea"))
        )
        val results = RecallEngine.rank(query, "do I like green tea?", listOf(memory))
        assertEquals(1, results.size)
        assertTrue(results[0].score >= RecallEngine.MIN_SCORE)
        assertTrue("similarity" in results[0].reason)
        assertTrue("high" in results[0].reason)
    }

    @Test
    fun recallDropsBelowMinScore() = runTest {
        val embedder = LocalHashEmbedder(dimensions = 128)
        val query = embedder.getEmbedding("completely unrelated query words here")
        val memory = MemoryItem(
            content = "green tea preference",
            timestamp = 0,
            embedding = VectorCodec.encode(embedder.getEmbedding("green tea preference"))
        )
        val results = RecallEngine.rank(query, "completely unrelated query words here", listOf(memory))
        assertTrue(results.isEmpty() || results.all { it.score >= RecallEngine.MIN_SCORE })
    }

    @Test
    fun rememberCommandsParse() {
        assertEquals("i like tea", MemoryCommandParser.parse("Remember that I like tea")?.content)
        assertNull(MemoryCommandParser.parse("remember this")?.content)
        assertNull(MemoryCommandParser.parse("remember")?.content)
        assertNull(MemoryCommandParser.parse("what's the time"))
        assertEquals("buy milk", MemoryCommandParser.parse("note that buy milk")?.content)
        assertEquals("the meeting", MemoryCommandParser.parse("don't forget the meeting")?.content)
    }

    @Test
    fun textNormalizerAndDisplayName() {
        assertEquals("remember that i like tea", MemoryText.normalize("Remember THAT,  I like Tea!"))
        assertEquals("Amy Lee", MemoryText.displayName("  aMy   lee "))
    }

    @Test
    fun promptBuilderAssemblesPersonaMemoriesAndHistory() {
        val prompt = PromptBuilder.build(
            companionName = "Memba",
            userInput = "what tea do I like?",
            recentMessages = listOf(
                ChatMessage(MessageRole.USER, "hello", speakerName = "amy lee"),
                ChatMessage(MessageRole.ASSISTANT, "Hi!")
            ),
            recalled = listOf(
                com.meowmba.shared.memory.RecallResult(
                    memory = MemoryItem(content = "User likes green tea", timestamp = 1_700_000_000_000),
                    score = 0.8f,
                    reason = "matched"
                )
            ),
            nowEpochMillis = 1_700_000_000_000,
            utcOffsetMinutes = 0,
            capabilities = listOf("memory.search: search remembered facts")
        )
        assertTrue("You are a helpful AI assistant" in prompt)
        assertTrue("Memba: Hi!" in prompt)
        assertTrue("Amy Lee: hello" in prompt)
        assertTrue("User likes green tea" in prompt)
        assertTrue("memory.search" in prompt)
        assertTrue("what tea do I like?" in prompt)
        assertTrue(prompt.trimEnd().endsWith("Memba:"))
    }

    @Test
    fun promptBuilderClockLineGroundsTime() {
        val prompt = PromptBuilder.build(
            companionName = "Memba",
            userInput = "what time is it",
            recentMessages = emptyList(),
            recalled = emptyList(),
            nowEpochMillis = 0,  // epoch
            utcOffsetMinutes = 0
        )
        assertTrue("Thursday, January 1, 1970" in prompt)
    }

    @Test
    fun batteryGateAndDeviceFacts() {
        assertTrue(PromptBuilder.requestsBatteryStatus("how much battery is left"))
        assertTrue(PromptBuilder.requestsBatteryStatus("charge level?"))
        assertTrue(!PromptBuilder.requestsBatteryStatus("what is the capital of France"))

        val facts = PromptBuilder.deviceFacts("Battery: 80%", robotLive = false, robotStatus = "not paired")
        assertTrue("No robot body is connected" in facts)
        assertTrue("never invent" in facts)
    }

    @Test
    fun dateTextFormatsKnownInstants() {
        // 1970-01-01T00:00:00Z
        assertEquals("1970-01-01", DateText.date(0))
        // 2026-09-15T20:00:00-07:00 == 2026-09-16T03:00:00Z
        val millis = DateTextTest.epoch(2026, 9, 16, 3, 0)
        assertEquals("2026-09-15", DateText.date(millis, utcOffsetMinutes = -7 * 60))
        assertEquals("2026-09-16", DateText.date(millis, utcOffsetMinutes = 0))
    }

    @Test
    fun dateTextWeekdayAndHourMatchCivilCalendar() {
        // 2026-08-12T08:15:00Z — the fixture IdleChatter's ported tests use.
        val t = DateTextTest.epoch(2026, 8, 12, 8, 15)
        assertEquals("Wednesday", DateText.weekdayName(t, 0))
        assertEquals(8, DateText.hourOfDay(t, 0))

        // Same instant at UTC-7 (MST): 01:15, still Wednesday.
        assertEquals("Wednesday", DateText.weekdayName(t, -7 * 60))
        assertEquals(1, DateText.hourOfDay(t, -7 * 60))

        // 2026-08-12T02:00Z at UTC-7 is 19:00 the *previous* day — the offset
        // must move the weekday too, not just the hour.
        val lateNight = DateTextTest.epoch(2026, 8, 12, 2, 0)
        assertEquals("Tuesday", DateText.weekdayName(lateNight, -7 * 60))
        assertEquals(19, DateText.hourOfDay(lateNight, -7 * 60))

        // 2026-08-12T23:59Z at UTC+1 rolls forward into Thursday 00:59.
        val lateEvening = DateTextTest.epoch(2026, 8, 12, 23, 59)
        assertEquals("Thursday", DateText.weekdayName(lateEvening, 60))
        assertEquals(0, DateText.hourOfDay(lateEvening, 60))

        // Epoch day 0 is a Thursday (see WEEKDAYS' index comment).
        assertEquals("Thursday", DateText.weekdayName(0, 0))
        assertEquals(0, DateText.hourOfDay(0, 0))
    }

    @Test
    fun dateTextHourIsCorrectBeforeTheEpoch() {
        // Negative millis are where truncating division silently gives the wrong
        // answer: -3_600_000 / 60_000 == -60, and floorMod(-60, 1440) == 1380,
        // so the hour is 23 — not -1. A naive `/` + `%` returns -1 here.
        val beforeEpoch = DateTextTest.epoch(1969, 12, 31, 23, 0)
        assertTrue(beforeEpoch < 0, "fixture should precede the epoch")
        assertEquals(23, DateText.hourOfDay(beforeEpoch, 0))
        assertEquals("Wednesday", DateText.weekdayName(beforeEpoch, 0))
    }

    @Test
    fun exporterProducesEscapedJson() {
        val json = MemoryExporter.toJson(
            listOf(
                MemoryItem(id = 7, content = "likes \"quotes\"\nand newlines", timestamp = 0, tags = listOf("tea", "pref"))
            )
        )
        assertTrue("\"id\": 7" in json)
        assertTrue("\\\"quotes\\\"" in json)
        assertTrue("\\nand newlines" in json)
        assertTrue("\"tags\": [\"tea\", \"pref\"]" in json)
    }
}

/** Small helper: UTC millis from civil components (for test vectors). */
object DateTextTest {
    fun epoch(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        // days from civil (Howard Hinnant)
        var y = year.toLong()
        val m = month.toLong()
        if (m <= 2) y -= 1
        val era = y.floorDiv(400)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468
        return days * 86_400_000 + hour * 3_600_000 + minute * 60_000
    }
}
