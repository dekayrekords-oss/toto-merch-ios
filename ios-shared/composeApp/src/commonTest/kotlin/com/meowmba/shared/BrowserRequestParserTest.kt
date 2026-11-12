package com.meowmba.shared

import com.meowmba.shared.work.BrowserJob
import com.meowmba.shared.work.BrowserRequestParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Ported from the original's `BrowserRequestParserTest.kt` (4 tests, all
 * ported). The parser lowercases internally, so — like the original — these
 * pass raw text rather than a normalized string.
 */
class BrowserRequestParserTest {

    private fun p(s: String) = BrowserRequestParser.parse(s)

    @Test
    fun makeAPageIsABuildJob() {
        val r = p("make a page about cats")!!
        assertEquals(BrowserJob.BUILD, r.job)
        val site = p("can you build a website for the bakery")!!
        assertEquals(BrowserJob.BUILD, site.job)
        assertNotNull(p("write a landing page"))
        assertNotNull(p("design a webpage"))
        assertEquals(BrowserJob.BUILD, p("create a website")!!.job)
        assertEquals(BrowserJob.BUILD, p("make a web site for the bakery")!!.job)
        assertEquals(BrowserJob.BUILD, p("generate a site about cats")!!.job)
    }

    @Test
    fun scrapePullsAUrl() {
        val r = p("scrape https://example.com/news")!!
        assertEquals(BrowserJob.SCRAPE, r.job)
        assertEquals("https://example.com/news", r.url)
        assertEquals(BrowserJob.SCRAPE, p("extract the text from this page")!!.job)
        assertEquals(BrowserJob.SCRAPE, p("pull the links")!!.job)
    }

    @Test
    fun openTheAgentBrowser() {
        assertEquals(BrowserJob.OPEN, p("open the browser")!!.job)
        assertEquals(BrowserJob.OPEN, p("hey meow open the agent browser")!!.job)
    }

    @Test
    fun doesNotStealNearbyPhrases() {
        assertNull(p("how to scrape a website"))
        assertNull(p("how to create a website"))
        assertNull(p("what is a web page"))
        assertNull(p("start a project"))
        assertNull(p("open spotify"))
        assertNull(p("tell me a fact"))
    }

    /**
     * Port-only, and the reason `extractUrl` is `internal` rather than private.
     * The ported suite above only ever sees a bare URL; the trimming and the
     * `www.` promotion are the parts that silently rot.
     */
    @Test
    fun urlExtractionTrimsTrailingPunctuationAndPromotesBareWww() {
        assertEquals("https://example.com/news", BrowserRequestParser.extractUrl("scrape https://example.com/news"))
        assertEquals("https://example.com", BrowserRequestParser.extractUrl("see https://example.com."))
        assertEquals("https://example.com", BrowserRequestParser.extractUrl("see https://example.com,"))
        assertEquals("https://example.com", BrowserRequestParser.extractUrl("see https://example.com)"))
        assertEquals("https://example.com/a", BrowserRequestParser.extractUrl("see https://example.com/a]"))
        assertEquals("https://www.example.com/x", BrowserRequestParser.extractUrl("www.example.com/x"))
        assertNull(BrowserRequestParser.extractUrl("no link here"))
        // Only the first match is taken, and only a scheme or a bare `www.` counts.
        assertEquals("https://a.test", BrowserRequestParser.extractUrl("https://a.test and https://b.test"))
        assertNull(BrowserRequestParser.extractUrl("example.com/x"))
    }
}
