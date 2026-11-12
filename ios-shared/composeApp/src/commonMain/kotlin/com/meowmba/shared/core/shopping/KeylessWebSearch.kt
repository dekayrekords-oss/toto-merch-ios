package com.meowmba.shared.core.shopping



/**
 * Keyless multi-engine web search — a Kotlin port of the portable core of
 * donsetch (github.com/dondai44423/donsetch), the no-keys/no-accounts Rust
 * search+crawl agent.
 *
 * What survives the port (the genuinely portable part): scraper-based parsers
 * over several FREE engines (DuckDuckGo lite/html, Bing, Brave, Mojeek),
 * redirect-target decoding, a "<3 hits = engine failed" quality bar, and
 * cross-engine result fusion. What does NOT port: donsetch's Chrome-TLS
 * fingerprinting, headless-browser solve-and-bounce, PDF OCR, and ONNX
 * reranker — those need the Rust binary and are out of scope for the phone.
 *
 * Pure JVM except the fetch itself (returns raw HTML strings); every parser
 * here is unit-testable on the desktop, matching the repo's "pure where
 * possible" rule.
 */

/** One raw hit from one engine. */
data class WebHit(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String,
)

object KeylessWebSearch {

    /** Build the free-SERP URL for an engine. Mirrors donsetch's regress table. */
    fun searchUrl(engine: String, query: String): String? {
        val q = com.meowmba.shared.voice.UrlCodec.encodeQuery(query)
        return when (engine) {
            "ddg_lite" -> "https://lite.duckduckgo.com/lite/?q=$q"
            "ddg_html" -> "https://html.duckduckgo.com/html/?q=$q"
            "bing" -> "https://www.bing.com/search?q=$q&count=15"
            "brave" -> "https://search.brave.com/search?q=$q"
            "mojeek" -> "https://www.mojeek.com/search?q=$q"
            else -> null
        }
    }

    /**
     * Parse one engine's SERP HTML into hits. Returns fewer than [minHits]
     * when the engine served a CAPTCHA/empty page — the caller treats that as
     * a failed engine and fuses the rest, never a lie.
     */
    fun parse(engine: String, html: String, minHits: Int = 3): List<WebHit> =
        when (engine) {
            "ddg_lite" -> parseDdgLite(html)
            "ddg_html" -> parseDdgHtml(html)
            "bing" -> parseBing(html)
            "brave" -> parseBrave(html)
            "mojeek" -> parseMojeek(html)
            else -> emptyList()
        }.takeIf { it.size >= minHits }.orEmpty()

    /** Fusion: keep one entry per URL, folding in the first snippet and engine tag. */
    fun fuse(hits: List<WebHit>): List<WebHit> {
        val byUrl = LinkedHashMap<String, WebHit>()
        for (hit in hits) {
            val real = realUrl(hit.url)
            if (real.isBlank()) continue
            val prev = byUrl[real]
            byUrl[real] = if (prev == null) {
                hit.copy(url = real)
            } else {
                prev.copy(snippet = prev.snippet.ifBlank { hit.snippet })
            }
        }
        return byUrl.values.toList()
    }

    /**
     * Decode the redirect wrappers the engines put around real URLs:
     *   - DDG lite/html: `/l/?uddg=<url-encoded-target>`
     *   - Bing: `/ck/a?...&u=a1<base64url>` (also bing.com/ck/a stubs)
     *   - Brave/Mojeek: plain http(s) links, left alone
     */
    fun realUrl(raw: String): String {
        val t = raw.trim()
        val uddg = Regex("""[?&]uddg=([^&]+)""").find(t)?.groupValues?.get(1)
        if (uddg != null) {
            val dec = percentDecode(uddg)
            return if (dec.startsWith("http")) dec else t
        }
        if ("bing.com/ck/a" in t || "bing.com/ck/" in t) {
            val u = Regex("""[?&]u=([^&]+)""").find(t)?.groupValues?.get(1)
            if (u != null) {
                val b64 = u.removePrefix("a1")
                val dec = base64UrlDecode(b64)
                if (dec.startsWith("http")) return dec
            }
            return t
        }
        return t
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parsers (scraper-lite, one per engine)
    // ─────────────────────────────────────────────────────────────────────

    private fun parseDdgLite(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        for (block in html.split("<tr class=\"result\">").drop(1)) {
            val link = extractAttr(block, "a", "href")
            val title = extractTextOf(block, "a")
            val snippet = stripHtml(block.substringAfter("</a>").substringBefore("</tr>"))
            if (link != null && title.isNotBlank()) {
                out.add(WebHit(title, link, collapse(snippet), "ddg_lite"))
            }
        }
        return out
    }

    private fun parseDdgHtml(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        // html.duckduckgo.com wraps each result in a <div class="result">.
        for (block in html.split("<div class=\"result").drop(1)) {
            val link = extractAttr(block, "a", "href")
            val title = extractTextOf(block, "a")
            val snippet = stripHtml(block)
            if (link != null && title.isNotBlank()) {
                out.add(WebHit(title, link, collapse(snippet.take(300)), "ddg_html"))
            }
        }
        return out
    }

    private fun parseBing(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        // Bing results live in <li class="b_algo"> blocks.
        for (block in html.split("<li class=\"b_algo").drop(1)) {
            val link = extractAttr(block, "a", "href")
            val title = extractTextOf(block, "a")
            val snippet = stripHtml(block)
            if (link != null && title.isNotBlank()) {
                out.add(WebHit(title, link, collapse(snippet.take(300)), "bing"))
            }
        }
        return out
    }

    private fun parseBrave(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        for (block in html.split("<div class=\"snippet").drop(1)) {
            val link = extractAttr(block, "a", "href")
            val title = extractTextOf(block, "a")
            val snippet = stripHtml(block)
            if (link != null && title.isNotBlank()) {
                out.add(WebHit(title, link, collapse(snippet.take(300)), "brave"))
            }
        }
        return out
    }

    private fun parseMojeek(html: String): List<WebHit> {
        val out = mutableListOf<WebHit>()
        // Mojeek: <li class="result"> with the URL in data-url or a title href.
        for (block in html.split("<li class=\"result\"").drop(1)) {
            val link = (extractAttr(block, "a", "href") ?: extractAttr(block, "a", "data-url"))?.takeIf {
                it.startsWith("http")
            }
            val title = extractTextOf(block, "a")
            val snippet = stripHtml(block)
            if (link != null && title.isNotBlank()) {
                out.add(WebHit(title, link, collapse(snippet.take(300)), "mojeek"))
            }
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTML helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun extractAttr(html: String, tag: String, attr: String): String? {
        val re = Regex("<$tag[^>]*$attr=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return re.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun extractTextOf(html: String, tag: String): String {
        val pattern = "<" + tag + "[^>]*>(.*?)</" + tag + ">"
        val re = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val inner = re.find(html)?.groupValues?.get(1)
        return collapse(stripHtml(inner ?: ""))
    }

    private fun stripHtml(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    private fun collapse(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    private fun percentDecode(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                out.append(hex.toIntOrNull(16)?.toChar() ?: c)
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun base64UrlDecode(s: String): String {
        val clean = s.map { if (it == '-') '+' else if (it == '_') '/' else it }.joinToString("")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return try {
            b64Decode(padded).decodeToString()
        } catch (e: Exception) {
            ""
        }
    }

    /** Hand-rolled standard base64 (android.util.Base64 is not in JVM tests). */
    private val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private fun b64Decode(s: String): ByteArray {
        val table = IntArray(128) { -1 }
        for (i in B64_ALPHABET.indices) table[B64_ALPHABET[i].code] = i
        val clean = s.trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) throw IllegalArgumentException("bad base64: '$c'")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
