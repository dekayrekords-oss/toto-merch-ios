package com.meowmba.shared.work

import com.meowmba.shared.voice.UrlCodec

/**
 * "Ask Alexa to …" / "tell ChatGPT …" — hand the request to another assistant.
 *
 * Meow cannot sit inside those apps. It opens them (or their web UI) with the
 * user's words. If the app is missing, the web page still carries the query.
 *
 * Ported from `core/work/AssistantRelay.kt`. The only change is [Target.webUrl],
 * which used `java.net.URLEncoder.encode(query, "UTF-8")` — JVM-only — and now
 * calls [UrlCodec.encodeQuery]. See that call site for the one divergence this
 * port had to close.
 */
data class AssistantRelay(
    val target: Target,
    val query: String,
) {
    enum class Target(
        val spoken: String,
        val packageName: String,
        val aliases: Set<String>,
    ) {
        ALEXA("Alexa", "com.amazon.dee.app", setOf("alexa", "echo", "amazon alexa")),
        GOOGLE("Google", "com.google.android.googlequicksearchbox", setOf("google", "google assistant", "assistant")),
        GEMINI("Gemini", "com.google.android.apps.bard", setOf("gemini", "bard")),
        CHATGPT("ChatGPT", "com.openai.chatgpt", setOf("chatgpt", "chat gpt", "gpt", "openai")),
        CLAUDE("Claude", "com.anthropic.claude", setOf("claude", "anthropic")),
        ;

        /**
         * `packageName` is retained as data even though this port cannot launch a
         * package: the alias table is the parser's whole vocabulary, and the
         * spoken name is what the UI shows.
         */
        fun webUrl(query: String): String {
            val q = UrlCodec.encodeQuery(query)
            return when (this) {
                ALEXA -> "https://alexa.amazon.com"
                GOOGLE -> "https://www.google.com/search?q=$q"
                GEMINI -> "https://gemini.google.com/app?q=$q"
                CHATGPT -> "https://chatgpt.com/?q=$q"
                CLAUDE -> "https://claude.ai/new?q=$q"
            }
        }
    }

    companion object {
        fun parse(text: String): AssistantRelay? {
            val t = text.trim().lowercase().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) return null
            val m = Regex(
                "^(?:hey meow |ok meow |please )?" +
                    "(?:ask|tell|relay to|send to|pass to|have) " +
                    "(.+?) " +
                    "(?:to |that |if )?" +
                    "(.+)$",
            ).find(t) ?: return null
            val who = m.groupValues[1].trim()
            val query = m.groupValues[2].trim()
            if (query.length < 2) return null
            // Defence in depth, and *unobservable by construction* — recorded
            // rather than left looking pinned. Deleting this line fails no test:
            // no alias in [Target] is "me", "meow" or "memba", and none is a
            // suffix of them either, so the alias lookup below already returns
            // null for every name this line rejects. Mutation testing confirmed
            // it (M11 survived). It is kept because it states the intent — never
            // relay the user's words to the companion itself — and because
            // "memba" is one of the wake names, so a future alias could easily
            // make this load-bearing without anyone revisiting the parser.
            if (who in setOf("me", "meow", "memba")) return null
            val target = Target.entries.firstOrNull { cand ->
                cand.aliases.any { alias -> who == alias || who.endsWith(" $alias") }
            } ?: return null
            return AssistantRelay(target, query)
        }
    }
}
