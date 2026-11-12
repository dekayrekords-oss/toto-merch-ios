package com.meowmba.shared.core.northstar

import kotlinx.serialization.Serializable

@Serializable
data class Ingredient(val text: String, val doneAt: Long? = null)

@Serializable
data class NorthStar(
    val id: String,
    val title: String,
    val why: String,
    val horizon: String,
    val ingredients: List<Ingredient>,
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long = 0,
) {
    fun ingredients(): List<Ingredient> = ingredients
}

@Serializable
data class NorthStarLink(
    val id: String,
    val starId: String,
    val sourceKey: String,
    val text: String,
    val at: Long,
    val reason: String,
    /** CONNECTION is evidence of relevance, never evidence of achievement. */
    val kind: String,
    val engine: String,
    /** A tombstone prevents a dismissed connection returning on the next refresh. */
    val dismissed: Boolean = false,
)

data class StarEvidence(val key: String, val text: String, val at: Long)
data class StarMatch(val reason: String, val kind: String = "CONNECTION", val engine: String = "rules")
data class StarView(val star: NorthStar, val links: List<NorthStarLink>, val momentum: String,
                    val nextStep: String, val weeklyReview: String, val opportunity: NorthStarLink?)

/** Android NorthStarPolicy (build 172). No generated facts or completion percentages. */
object NorthStarPolicy {
    const val DAY = 86_400_000L
    private val explicit = Regex("^(?:hey meow[,!]?\\s*)?(?:my (?:dream|goal|ambition) is (?:to )?|one day (?:i(?:'ll| will)|i(?:'d| would) like to) |someday i (?:want to|will) )(.*)", RegexOption.IGNORE_CASE)
    private val want = Regex("^i want (?:to )?(.+)", RegexOption.IGNORE_CASE)
    private val lasting = Regex("\\b(band|career|business|degree|graduate|marathon|learn|become|build|publish|album|tour|europe|save up|buy a home|buy a house|move to|retire)\\b", RegexOption.IGNORE_CASE)
    private val command = Regex("^(?:you to |(?:open|scan|read|tell|show|check|send|delete|close|turn|hear|know|see|install|update|download|fix|make me)\\b)", RegexOption.IGNORE_CASE)
    private val ignore = setOf("want", "would", "like", "with", "this", "that", "have", "from", "about", "will", "into", "your", "their", "some", "goal", "dream", "make", "more", "time", "need", "been", "were", "then")

    fun capture(text: String): String? {
        val t = text.trim().replace('’', '\'')
        if (t.contains('?') || t.contains('\n') || t.length > 350) return null
        val match = explicit.find(t)?.groupValues?.get(1) ?: want.find(t)?.groupValues?.get(1)?.takeIf { lasting.containsMatchIn(it) }
        return match?.trim()?.trimEnd('.', '!')?.takeIf { it.length in 8..240 && !command.containsMatchIn(it) }
    }

    fun suggestedIngredients(title: String): List<Ingredient> {
        val band = Regex("\\b(band|music|tour|album)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
        val suggestions = if (band) listOf("Record one rehearsal for the live set", "Choose a live video for a press kit",
            "Find one booker or festival contact", "Plan a travel fund", "Check passports and time off")
        else listOf("Write down one concrete milestone", "Choose one person or resource that can help", "Set aside ten minutes for the first step")
        return suggestions.map(::Ingredient)
    }

    fun terms(text: String): Set<String> = Regex("[\\p{L}\\p{N}]{4,}").findAll(text.lowercase())
        .map { it.value }.filter { it !in ignore }.toSet()

    fun match(star: NorthStar, e: StarEvidence): StarMatch? {
        if (e.text.isBlank() || e.at < star.createdAt - DAY) return null
        val shared = terms(star.title + " " + star.ingredients().joinToString(" ") { it.text }).intersect(terms(e.text))
        val phrase = star.ingredients().map { it.text.trim() }.firstOrNull { it.length >= 8 && e.text.contains(it, ignoreCase = true) }
        if (shared.size < 2 && phrase == null) return null
        val basis = phrase?.let { "mentions “${it.take(100)}”" } ?: "mentions " + shared.take(3).joinToString(" and ") { "“$it”" }
        return StarMatch("This $basis, which connects to “${star.title}”.", kind(e))
    }

    /** Wording never upgrades an intention, failure or closed door into progress. */
    fun kind(e: StarEvidence): String {
        val t = e.text.lowercase().replace('’', '\'')
        if (e.key.startsWith("journal:") && Regex("\\b(i|we) missed (?:the |an? |our )?(deadline|audition|invitation|opening|application)\\b").containsMatchIn(t)) return "MISSED"
        val futureOrNegative = Regex(
            "\\b(not|no|never|none|without|haven't|hasn't|didn't|doesn't|don't|won't|can't|cannot|couldn't|" +
                "need to|want to|plan to|going to|will|could|might|should|failed|failure|cancelled|canceled|" +
                "declined|rejected|missed|closed|passed|expired|withdrawn|unable|almost|pending)\\b"
        ).containsMatchIn(t) || '?' in t
        if (e.key.startsWith("journal:") && !futureOrNegative &&
            Regex("\\b(finished|completed|recorded|rehearsed|played|sold out|saved|booked|released|submitted|met)\\b").containsMatchIn(t)
        ) return "PROGRESS"
        if (!futureOrNegative &&
            Regex("\\b(invitation|invited|applications open|deadline|booker|booking|promoter|audition|apply by)\\b").containsMatchIn(t)
        ) return "OPPORTUNITY"
        return "CONNECTION"
    }

    /** Stable per goal + source (FNV-1a 64; Android uses SHA-256 — only uniqueness matters). */
    fun linkId(star: NorthStar, evidence: StarEvidence): String {
        var h = -0x340d631b7bdddcdbL
        for (b in (star.id + "\u0000" + evidence.key).encodeToByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
        return h.toULong().toString(16).padStart(16, '0')
    }

    fun view(star: NorthStar, all: List<NorthStarLink>, now: Long): StarView {
        val links = all.filter { it.starId == star.id && !it.dismissed }.sortedByDescending { it.at }
        val week = links.filter { it.at in (now - 7 * DAY)..now }
        val done = star.ingredients().mapNotNull { it.doneAt }.filter { it <= now }
        val last = (links.filter { it.kind == "PROGRESS" && it.at <= now }.map { it.at } + done).maxOrNull()
        val days = ((now - (last ?: star.createdAt)).coerceAtLeast(0) / DAY).toInt()
        val momentum = when {
            last != null && days < 7 -> "Moving · progress recorded this week"
            days < 7 -> "Getting started · no progress recorded yet"
            else -> "Quiet for ${days / 7} ${if (days / 7 == 1) "week" else "weeks"} · no recent progress recorded"
        }
        val door = week.firstOrNull { it.kind == "OPPORTUNITY" }
        val first = star.ingredients().firstOrNull { it.doneAt == null }
        val next = if (door != null) "Review this opening: ${door.text.take(140)}"
            else first?.text ?: "Choose the next milestone for this goal"
        val progress = week.count { it.kind == "PROGRESS" } + done.count { it >= now - 7 * DAY }
        val review = "Past 7 days: $progress progress ${if (progress == 1) "entry" else "entries"}, ${week.count { it.kind == "OPPORTUNITY" }} possible openings. " +
            (if (progress == 0) "No completed step recorded. " else "See the evidence below. ") +
            "${week.count { it.kind == "MISSED" }} missed openings reported. One focus: $next."
        return StarView(star, links, momentum, next, review, door)
    }
}
