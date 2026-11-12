package com.meowmba.shared

import com.meowmba.shared.work.AssistantRelay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ported from the original's `AssistantRelayTest.kt` (3 tests, all ported), plus
 * one port-only test for [AssistantRelay.Target.webUrl], which the original
 * never covered.
 *
 * `parse` lowercases internally, so — like the original — these pass raw text.
 */
class AssistantRelayTest {

    @Test
    fun askAlexaToDoesAThing() {
        val r = AssistantRelay.parse("ask alexa to turn off the lights")!!
        assertEquals(AssistantRelay.Target.ALEXA, r.target)
        assertEquals("turn off the lights", r.query)
    }

    @Test
    fun tellChatgptAndGeminiAndClaude() {
        assertEquals(
            AssistantRelay.Target.CHATGPT,
            AssistantRelay.parse("tell chatgpt to write a haiku")!!.target,
        )
        assertEquals(
            AssistantRelay.Target.GEMINI,
            AssistantRelay.parse("ask gemini what a capacitor is")!!.target,
        )
        assertEquals(
            "explain rust",
            AssistantRelay.parse("ask claude to explain rust")!!.query,
        )
    }

    /**
     * Ported, and **it does not pin what its name suggests.** Deleting
     * `parse`'s `if (who in setOf("me", "meow", "memba")) return null` line
     * leaves this test green (mutation M11), because no [AssistantRelay.Target]
     * alias is one of those names or ends in one — so the alias lookup refuses
     * them anyway. The guard is defence in depth, labelled as such in the
     * source. Kept as a ported test because it pins the *behaviour* ("ask meow"
     * is not a relay), which is what matters; the guard that happens to
     * implement it is not the invariant.
     */
    @Test
    fun askMeowItselfIsNotARelay() {
        assertNull(AssistantRelay.parse("ask meow to set a timer"))
        assertNull(AssistantRelay.parse("open spotify"))
    }

    /**
     * Port-only. The original built these URLs with
     * `java.net.URLEncoder.encode(query, "UTF-8")`; the port calls
     * [com.meowmba.shared.voice.UrlCodec.encodeQuery]. Nothing in the original
     * suite pinned the encoded form, so the substitution was unverified — this
     * is the test that makes it verified. The expected strings are the JDK's own
     * output, checked against a throwaway `java.net.URLEncoder` program rather
     * than recalled.
     *
     * Alexa carries no query at all: it opens its own page and the user repeats
     * themselves there.
     */
    @Test
    fun relayUrlsCarryTheQueryEncodedLikeUrlEncoder() {
        assertEquals("https://alexa.amazon.com", AssistantRelay.Target.ALEXA.webUrl("turn off the lights"))
        assertEquals(
            "https://www.google.com/search?q=turn+off+the+lights",
            AssistantRelay.Target.GOOGLE.webUrl("turn off the lights"),
        )
        assertEquals(
            "https://gemini.google.com/app?q=what+is+a+capacitor",
            AssistantRelay.Target.GEMINI.webUrl("what is a capacitor"),
        )
        assertEquals(
            "https://chatgpt.com/?q=write+a+haiku",
            AssistantRelay.Target.CHATGPT.webUrl("write a haiku"),
        )
        assertEquals(
            "https://claude.ai/new?q=explain+rust",
            AssistantRelay.Target.CLAUDE.webUrl("explain rust"),
        )
        // A query that reaches `webUrl` through `parse` keeps its encoding too.
        val relay = AssistantRelay.parse("ask chatgpt to explain rust & c++")!!
        assertEquals("https://chatgpt.com/?q=explain+rust+%26+c%2B%2B", relay.target.webUrl(relay.query))
    }
}
