package com.meowmba.shared

import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.StreamParsers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamParsersTest {

    @Test
    fun ollamaPartialAndDone() {
        val partial = StreamParsers.parseOllamaLine("""{"response":"Hel","done":false}""")
        assertIs<ModelResponse.Partial>(partial)
        assertEquals("Hel", partial.text)

        val done = StreamParsers.parseOllamaLine("""{"response":"lo","done":true}""")
        assertIs<ModelResponse.Complete>(done)
        assertEquals("lo", done.text)
    }

    @Test
    fun ollamaNoiseYieldsNull() {
        assertNull(StreamParsers.parseOllamaLine(""))
        assertNull(StreamParsers.parseOllamaLine("   "))
        assertNull(StreamParsers.parseOllamaLine("not json"))
        assertNull(StreamParsers.parseOllamaLine("""{"done":false}"""))
    }

    @Test
    fun openAiSseContent() {
        val line = """data: {"choices":[{"delta":{"content":"hi"}}]}"""
        val result = StreamParsers.parseOpenAISseLine(line)
        assertIs<ModelResponse.Partial>(result)
        assertEquals("hi", result.text)
    }

    @Test
    fun openAiDoneMarker() {
        val result = StreamParsers.parseOpenAISseLine("data: [DONE]")
        assertIs<ModelResponse.Complete>(result)
    }

    @Test
    fun openAiMidStreamErrorSurfaces() {
        val line = """data: {"error":{"message":"rate limited"}}"""
        val result = StreamParsers.parseOpenAISseLine(line)
        assertIs<ModelResponse.Error>(result)
        assertTrue("rate limited" in result.message)
    }

    @Test
    fun nonDataLinesIgnored() {
        assertNull(StreamParsers.parseOpenAISseLine(": keepalive"))
        assertNull(StreamParsers.parseOpenAISseLine("event: ping"))
        assertNull(StreamParsers.parseOpenAISseLine(""))
    }

    @Test
    fun apiErrorMessageExtracted() {
        assertEquals(
            "quota exceeded",
            StreamParsers.extractApiErrorMessage("""{"error":{"message":"quota exceeded","code":429}}""")
        )
        assertEquals("plain boom", StreamParsers.extractApiErrorMessage("plain boom"))
        assertEquals("no detail", StreamParsers.extractApiErrorMessage("  "))
    }

    @Test
    fun modelCounters() {
        assertEquals(2, StreamParsers.countOllamaModels("""{"models":[{"name":"a"},{"name":"b"}]}"""))
        assertEquals(1, StreamParsers.countOpenAIModels("""{"data":[{"id":"x"}]}"""))
        assertEquals(0, StreamParsers.countOllamaModels("garbage"))
    }
}
