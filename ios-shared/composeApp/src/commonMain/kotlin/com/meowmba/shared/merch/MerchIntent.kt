package com.meowmba.shared.merch

/** What a customer's utterance means at the merch table. Deterministic, offline, no model call. */
sealed class MerchIntent {
    object Greet : MerchIntent()
    object ListStock : MerchIntent()
    data class Recommend(val hint: String) : MerchIntent()
    data class Price(val sku: String?) : MerchIntent()
    /** sku/size may be null when the customer has not said them yet. */
    data class Add(val sku: String?, val size: String?, val qty: Int) : MerchIntent()
    data class SizeOnly(val size: String, val qty: Int?) : MerchIntent()
    data class Remove(val sku: String?, val size: String?) : MerchIntent()
    object ShowCart : MerchIntent()
    object Checkout : MerchIntent()
    object Confirm : MerchIntent()
    object Decline : MerchIntent()
    object CancelOrder : MerchIntent()
    data class SizeHelp(val sku: String?) : MerchIntent()
    /** Dismiss the merch menu and stay on the idle face. */
    object JustLooking : MerchIntent()
}

object MerchIntentParser {
    private val SIZE_PHRASES: List<Pair<String, String>> = listOf(
        // longest first so "extra large" wins over "large"
        "youth small" to "YS", "youth medium" to "YM", "youth large" to "YL",
        "kids small" to "YS", "kids medium" to "YM", "kids large" to "YL",
        "double extra large" to "2XL", "double xl" to "2XL", "extra extra large" to "2XL",
        "xx large" to "2XL", "xxl" to "2XL", "2xl" to "2XL", "2 xl" to "2XL",
        "extra large" to "XL", "x large" to "XL", "xl" to "XL",
        "small" to "S", "medium" to "M", "large" to "L",
        "ys" to "YS", "ym" to "YM", "yl" to "YL",
    )
    private val SINGLE_LETTER_SIZES = mapOf("s" to "S", "m" to "M", "l" to "L")

    private val NUMBER_WORDS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "couple" to 2, "pair" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )

    fun normalize(raw: String): String =
        raw.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun has(t: String, vararg phrases: String) = phrases.any { p -> Regex("(^| )" + Regex.escape(p) + "( |$)").containsMatchIn(t) }

    fun isJustLooking(raw: String): Boolean {
        val t = normalize(raw)
        return has(t,
            "just looking", "i'm just looking", "im just looking", "just browsing",
            "i'm just browsing", "im just browsing", "looking around", "just looking around",
            "not buying", "not buying anything", "don't want to buy", "do not want to buy",
            "maybe later", "not right now", "no thanks", "just looking thanks",
        )
    }

    fun isShopRequest(raw: String): Boolean {
        val t = normalize(raw)
        if (t.isEmpty() || isJustLooking(t)) return false
        return has(t,
            "how much", "price", "cost", "costs", "buy", "purchase", "i'll take", "ill take",
            "i want", "i'd like", "id like", "add", "what do you have", "show me", "menu",
            "for sale", "in stock", "recommend", "suggest", "check out", "checkout", "pay",
            "shirts", "tees", "merch", "see the", "see something", "looking for",
        ) || findItem(t) != null
    }

    fun findSize(t: String): String? {
        for ((phrase, size) in SIZE_PHRASES) if (has(t, phrase)) return size
        // single letters only in an explicit size context: "size m", "in an l", "an m"
        Regex("(?:size|in an?|an?) (s|m|l)( |$)").find(t)?.let { return SINGLE_LETTER_SIZES[it.groupValues[1]] }
        if (t in SINGLE_LETTER_SIZES) return SINGLE_LETTER_SIZES[t]
        return null
    }

    fun findItem(t: String): String? {
        // youth sizes / kid words point at the kids tee even without its name
        val scored = MerchCatalog.items.map { item ->
            item to item.keywords.count { has(t, it) } + (if (has(t, item.shortName.lowercase())) 3 else 0)
        }.filter { it.second > 0 }
        if (scored.isEmpty()) return null
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return if (top.size == 1) top[0].first.sku else null
    }

    fun findQty(t: String): Int? {
        Regex("(^| )(\\d{1,2})( |$)").find(t)?.let { m ->
            val n = m.groupValues[2].toInt()
            // "2xl" is handled as a size, and a lone "2" before "xl" too
            if (n in 1..20 && !Regex("(^| )" + n + " ?xl").containsMatchIn(t)) return n
        }
        for ((w, n) in NUMBER_WORDS) if (w != "a" && w != "an" && has(t, w)) return n
        return null
    }

    /** Returns null when the utterance is not about the merch table (let the normal chat answer). */
    fun parse(raw: String, awaitingConfirm: Boolean, awaitingSizeFor: String?): MerchIntent? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        val sku = findItem(t)
        val size = findSize(t)
        val qty = findQty(t)

        if (has(t, "cancel order", "cancel my order", "cancel the order", "start over", "never mind", "nevermind", "forget it", "clear cart", "empty cart"))
            return MerchIntent.CancelOrder

        if (awaitingConfirm) {
            if (has(t, "yes", "yeah", "yep", "yup", "confirm", "correct", "looks good", "looks right", "that's right", "sounds good", "perfect", "do it", "go ahead", "ok", "okay", "sure"))
                return MerchIntent.Confirm
            if (has(t, "no", "nope", "wait", "change", "wrong", "not right", "hold on")) return MerchIntent.Decline
        }

        if (isJustLooking(t)) return MerchIntent.JustLooking

        if (has(t, "remove", "take off", "take out", "drop the", "don't want", "do not want", "minus", "delete"))
            return MerchIntent.Remove(sku, size)

        if (has(t, "that's it", "that's all", "that is all", "that is it", "i'm done", "im done", "check out", "checkout", "ready to pay", "ring me up", "ring it up", "how do i pay", "pay now", "i want to pay", "let's pay", "finish"))
            return MerchIntent.Checkout

        if (has(t, "what's in my cart", "whats in my cart", "my cart", "my order", "what do i have", "what did i get", "my total", "the total", "what's the total", "whats the total"))
            return MerchIntent.ShowCart

        if (has(t, "size chart", "what size should", "which size", "how do they fit", "how does it fit", "fit", "runs small", "runs big", "true to size"))
            return MerchIntent.SizeHelp(sku)

        if (has(t, "how much", "price", "cost", "costs")) return MerchIntent.Price(sku)

        if (has(t, "recommend", "suggest", "what should i", "which one", "which should", "help me decide", "help me choose", "help me pick", "can't decide", "cant decide", "best one", "most popular", "favorite", "favourite", "gift", "present"))
            return MerchIntent.Recommend(t)

        val wantsToBuy = has(t, "i'll take", "ill take", "i will take", "i'll get", "ill get", "i want", "i'd like", "id like", "i would like", "can i get", "can i have", "give me", "add", "buy", "grab", "get me", "i'll have", "ill have")
        if (sku != null && (wantsToBuy || size != null)) return MerchIntent.Add(sku, size, qty ?: 1)
        if (wantsToBuy && size != null) return MerchIntent.Add(null, size, qty ?: 1)
        if (size != null && (awaitingSizeFor != null || t.split(' ').size <= 4)) return MerchIntent.SizeOnly(size, qty)
        if (wantsToBuy && has(t, "shirt", "tee", "t shirt", "tshirt", "one", "it", "that")) return MerchIntent.Add(null, null, qty ?: 1)

        if (has(t, "what do you have", "what have you got", "what you got", "what's for sale", "whats for sale", "for sale", "merch", "shirts", "t shirts", "tshirts", "tees", "options", "show me", "what do you sell", "selling", "menu", "in stock", "available"))
            return MerchIntent.ListStock

        if (sku != null) return MerchIntent.Price(sku)

        if (has(t, "hi", "hello", "hey", "howdy", "yo", "good morning", "good afternoon", "good evening", "hiya", "sup"))
            return MerchIntent.Greet
        return null
    }
}
