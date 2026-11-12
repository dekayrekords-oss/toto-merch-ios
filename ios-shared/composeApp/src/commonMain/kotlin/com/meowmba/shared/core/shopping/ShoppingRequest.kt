package com.meowmba.shared.core.shopping

/** Only explicit shopping questions/commands. Mentions, wishes and arrivals do not trigger it. */
data class ShoppingRequest(val store: String? = null, val item: String? = null) {
    companion object {
        fun parse(text: String): ShoppingRequest? {
            if (text.length > 500) return null
            val raw = text.trim().replace('’', '\'').replace(Regex("\\s+"), " ")
                .replace(Regex("[?.!]+$"), "")
                .replace(Regex("^(?:please |can you |could you )", RegexOption.IGNORE_CASE), "")
                .replace(Regex(" please$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^what(?:'s| is) on sale", RegexOption.IGNORE_CASE), "deals")
            val where = Regex("^(?:(?:tell me |do you know )?(?:what|which) (?:store|shop) (?:(?:am i|i'm|i am|are we|we're|we are) (?:in|at)|is this)|where am i shopping)(?: and (?:what are |show me )?(?:the )?(?:best )?deals(?: here)?)?$", RegexOption.IGNORE_CASE)
            if (where.matches(raw)) return ShoppingRequest()

            val deals = Regex("^(?:(?:what(?: are)?|show me|find me|find|check|look up) )?(?:the |any )?(?:best |current )?(?:deals|sales|coupons|offers)(.*)$", RegexOption.IGNORE_CASE)
                .matchEntire(raw) ?: return null
            var tail = deals.groupValues[1].trim()
            if (tail.isEmpty() || tail.lowercase() in setOf("here", "today", "right now", "in this store", "at this store", "near me", "are there here", "are available here", "does this store have"))
                return ShoppingRequest()
            tail = tail.replace(Regex(" (?:today|right now)$", RegexOption.IGNORE_CASE), "")
            Regex("^(?:on|for) (.+?) (?:at|from|in) (.+)$", RegexOption.IGNORE_CASE).matchEntire(tail)?.let {
                return build(it.groupValues[2], it.groupValues[1])
            }
            Regex("^(?:at|from|in) (.+?)(?: (?:on|for) (.+))?$", RegexOption.IGNORE_CASE).matchEntire(tail)?.let {
                return build(it.groupValues[1], it.groupValues[2].ifBlank { null })
            }
            Regex("^(?:on|for) (.+?)(?: here)?$", RegexOption.IGNORE_CASE).matchEntire(tail)?.let {
                return build(null, it.groupValues[1])
            }
            return null
        }

        private fun build(store: String?, item: String?): ShoppingRequest? {
            val named = store?.takeUnless { it.lowercase() in setOf("here", "this store", "this shop", "the store") }
            if (named != null && !validTerm(named, 80)) return null
            if (item != null && !validTerm(item, 100)) return null
            return ShoppingRequest(named, item)
        }

        // Do not allow URLs, instructions or coordinates to become a search destination/query.
        fun validTerm(term: String, maxLength: Int = 80): Boolean = term.length in 1..maxLength &&
            term.any { it.isLetter() } && term.all { it.isLetterOrDigit() || it in " '&-#()." } &&
            !term.contains(Regex("(?i)https?\\b|\\b(?:localhost|ignore instructions)\\b")) &&
            !term.contains(Regex("\\d+\\.\\d+")) && term.split(' ').size <= 12
    }
}
