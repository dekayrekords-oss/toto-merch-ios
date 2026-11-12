package com.meowmba.shared.work

/**
 * Voice that belongs in the swipe-left agent browser:
 * build a page, scrape a site, or just open the workshop.
 */
enum class BrowserJob { BUILD, SCRAPE, OPEN }

data class BrowserRequest(
    val job: BrowserJob,
    val goal: String,
    val url: String? = null,
)

/**
 * Ported from `core/work/BrowserRequestParser.kt`, unchanged. Pure Kotlin — no
 * JVM-only API is used, so nothing had to be substituted.
 */
object BrowserRequestParser {

    fun parse(text: String): BrowserRequest? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val t = stripLead(raw.lowercase().replace(Regex("\\s+"), " "))
        if (t.isEmpty()) return null
        val url = extractUrl(raw)
        return when {
            isBuild(t) -> BrowserRequest(BrowserJob.BUILD, t, url)
            isScrape(t) -> BrowserRequest(BrowserJob.SCRAPE, t, url)
            isOpen(t) -> BrowserRequest(BrowserJob.OPEN, t, url)
            else -> null
        }
    }

    internal fun extractUrl(t: String): String? {
        val m = Regex("""(https?://[^\s]+|www\.[^\s]+)""").find(t) ?: return null
        val raw = m.value.trimEnd('.', ',', ')', ']')
        return if (raw.startsWith("www.")) "https://$raw" else raw
    }

    private fun isBuild(t: String): Boolean {
        if (t.startsWith("create a project") || t.startsWith("start a project")) return false
        // Questions stay with chat: "how to create a website" is not a build.
        if (t.startsWith("how to ") || t.startsWith("what is ") || t.startsWith("what's ") ||
            t.startsWith("whats ")
        ) return false
        val page = t.contains("web page") || t.contains("webpage") ||
            t.contains("web site") || t.contains("landing page") ||
            t.contains("home page") || t.contains("web app") ||
            Regex("\\b(page|website|homepage|html|site)\\b").containsMatchIn(t)
        val make = Regex("\\b(make|build|design|write|draft|code|create|generate)\\b")
            .containsMatchIn(t)
        return make && page
    }

    private fun isScrape(t: String): Boolean {
        if (t.contains("how to scrape") || t.contains("what is scrape")) return false
        return Regex("\\b(scrape|scraping)\\b").containsMatchIn(t) ||
            t.contains("extract the text") || t.contains("extract the links") ||
            t.contains("extract the content") || t.contains("pull the text") ||
            t.contains("pull the links") || t.contains("grab the text") ||
            t.contains("grab the links")
    }

    private fun isOpen(t: String): Boolean {
        if (t.split(' ').size > 6) return false
        return t in setOf(
            "open the browser",
            "open browser",
            "open the agent browser",
            "show the browser",
            "open agent browser",
        )
    }

    private fun stripLead(raw: String): String {
        var s = raw
        val leads = listOf(
            "hey meow ", "ok meow ", "okay meow ", "meow ",
            "please ", "can you ", "could you ", "would you ", "hey ",
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in leads) {
                if (s.startsWith(p)) {
                    s = s.removePrefix(p)
                    changed = true
                }
            }
        }
        return s.trim()
    }
}
