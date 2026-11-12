package com.meowmba.shared.core.shopping

/** A source lead, not a verified price. A fresh search does not make an old coupon current. */
data class DealLead(val title: String, val url: String, val excerpt: String, val official: Boolean)

/** Android DealEvidence (build 177). [year]/[month]/[day] replace java.time.LocalDate. */
object DealEvidence {
    data class Day(val year: Int, val month: Int, val day: Int) : Comparable<Day> {
        override fun compareTo(other: Day) = compareValuesBy(this, other, Day::year, Day::month, Day::day)
        val monthYear get() = MONTHS[month - 1] + " " + year
        override fun toString() = "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
        companion object {
            private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
            /** Civil date for epoch millis at [offsetMinutes] (days-from-civil inverse). */
            fun of(millis: Long, offsetMinutes: Int): Day {
                val days = Math.floorDiv(millis + offsetMinutes * 60_000L, 86_400_000L)
                val z = days + 719468; val era = Math.floorDiv(z, 146097L); val doe = z - era * 146097
                val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
                val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
                val d = (doy - (153 * mp + 2) / 5 + 1).toInt(); val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
                return Day((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
            }
            fun parse(iso: String): Day? = Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(iso)?.let {
                Day(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }
        }
    }
    private object Math {
        fun floorDiv(a: Long, b: Long) = (a / b) - if ((a % b != 0L) && ((a xor b) < 0)) 1 else 0
    }

    private data class Retailer(val aliases: List<String>, val domains: List<String>)
    // Domain identity only, never hardcoded promotions/prices. Unknown shops still get a web search.
    private val retailers = listOf(
        Retailer(listOf("target"), listOf("target.com")),
        Retailer(listOf("walmart", "wal mart"), listOf("walmart.com")),
        Retailer(listOf("costco"), listOf("costco.com")),
        Retailer(listOf("sam's club", "sams club"), listOf("samsclub.com")),
        Retailer(listOf("best buy"), listOf("bestbuy.com")),
        Retailer(listOf("kroger"), listOf("kroger.com")),
        Retailer(listOf("fry's", "frys"), listOf("frysfood.com")),
        Retailer(listOf("safeway"), listOf("safeway.com")),
        Retailer(listOf("albertsons"), listOf("albertsons.com")),
        Retailer(listOf("bashas", "basha's"), listOf("bashas.com")),
        Retailer(listOf("sprouts"), listOf("sprouts.com")),
        Retailer(listOf("whole foods"), listOf("wholefoodsmarket.com")),
        Retailer(listOf("trader joe's", "trader joes"), listOf("traderjoes.com")),
        Retailer(listOf("aldi"), listOf("aldi.us")),
        Retailer(listOf("walgreens"), listOf("walgreens.com")),
        Retailer(listOf("cvs"), listOf("cvs.com")),
        Retailer(listOf("home depot", "the home depot"), listOf("homedepot.com")),
        Retailer(listOf("lowe's", "lowes"), listOf("lowes.com")),
        Retailer(listOf("kohl's", "kohls"), listOf("kohls.com")),
        Retailer(listOf("macy's", "macys"), listOf("macys.com")),
        Retailer(listOf("dollar general"), listOf("dollargeneral.com")),
        Retailer(listOf("dollar tree"), listOf("dollartree.com")),
        Retailer(listOf("family dollar"), listOf("familydollar.com")),
        Retailer(listOf("ikea"), listOf("ikea.com")),
    )

    private fun retailer(store: String): Retailer? {
        val name = store.lowercase().replace('’', '\'').trim()
        return retailers.firstOrNull { retailer -> retailer.aliases.any { alias ->
            name == alias || name.matches(Regex("${Regex.escape(alias)} (?:supercenter|superstore|marketplace|food(?: and drug)?|pharmacy|wholesale|#?\\d+)(?: #?\\d+)?"))
        } }
    }

    fun query(store: String, item: String?, today: Day): String {
        require(ShoppingRequest.validTerm(store))
        require(item == null || ShoppingRequest.validTerm(item, 100))
        val site = retailer(store)?.domains?.firstOrNull()?.let { "site:$it " }.orEmpty()
        val focus = item?.let { "\"$it\" deals coupons" } ?: "deals coupons weekly ad"
        return "$site\"$store\" $focus ${today.monthYear}"
    }

    fun select(store: String, item: String?, hits: List<WebHit>, today: Day): List<DealLead> {
        val domains = retailer(store)?.domains.orEmpty()
        val storeWords = words(store)
        val itemWords = item?.let(::words).orEmpty()
        return hits.mapNotNull { hit ->
            val host = safeHost(hit.url) ?: return@mapNotNull null
            val official = domains.any { host == it || host.endsWith(".$it") }
            val title = clean(hit.title, 180)
            val excerpt = clean(hit.snippet, 650)
            val text = "$title $excerpt"
            if (!official && !words(text).containsAll(storeWords)) return@mapNotNull null
            if (itemWords.isNotEmpty() && !words(text).containsAll(itemWords)) return@mapNotNull null
            if (!dealCue.containsMatchIn(text) || expired(text, today)) return@mapNotNull null
            DealLead(title.ifBlank { host }, hit.url, excerpt, official)
        }.distinctBy { it.url.substringBefore('#').trimEnd('/') }
            .sortedByDescending { if (it.official) 1 else 0 }.take(4)
    }

    /** https only, no credentials, no odd ports, no local hosts. */
    fun safeHost(url: String): String? {
        if (url.length > 2048) return null
        val m = Regex("^https://([^/?#]+)(?:[/?#].*)?$").matchEntire(url) ?: return null
        val authority = m.groupValues[1]
        if ('@' in authority) return null
        val host = authority.substringBefore(':').lowercase()
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && port != "443") return null
        if (!host.matches(Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}")) ||
            host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")) return null
        return host
    }

    private val dealCue = Regex("(?i)\\b(?:deal|deals|sale|sales|save|saving|savings|coupon|coupons|offer|offers|discount|clearance|weekly ad|bogo)\\b|\\d+%\\s*off")
    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase()).map { it.value }.filter { it !in setOf("the", "and", "s") }.toSet()
    private fun clean(text: String, max: Int): String = text.replace(Regex("<[^>]*>"), " ")
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    internal fun expired(text: String, today: Day): Boolean {
        if (Regex("(?i)\\b(?:offer|coupon|deal|sale) (?:has )?expired\\b|\\bexpired (?:offer|coupon|deal|sale)\\b").containsMatchIn(text)) return true
        if (Regex("\\b20\\d{2}\\b").findAll(text).any { it.value.toInt() < today.year }) return true
        val isoEnd = Regex("(?i)(?:ends?|expires?|valid (?:through|until))[: ]+(20\\d{2}-\\d{2}-\\d{2})")
        return isoEnd.findAll(text).any { match -> Day.parse(match.groupValues[1])?.let { it < today } ?: false }
    }
}

/** A store-like place near the phone (iOS: MapKit points of interest). */
data class Place(val name: String, val lat: Double, val lon: Double, val distanceMeters: Double? = null)
data class LocationFix(val lat: Double, val lon: Double, val accuracyMeters: Double?, val atMs: Long, val speedMps: Double?)

/** Android StoreEvidence: proximity is evidence of being NEAR a shop, never proof of being inside. */
sealed interface StoreEvidence {
    data class Likely(val place: Place) : StoreEvidence
    data class Ambiguous(val candidates: List<Place>) : StoreEvidence
    data class Unknown(val reason: String) : StoreEvidence

    companion object {
        const val RADIUS_METERS = 200
        const val MAX_AGE_MS = 30_000L
        const val MAX_ACCURACY_METERS = 60.0

        fun unusable(fix: LocationFix?, nowMs: Long): String? = when {
            fix == null -> "I don't have a recent, precise location fix."
            !fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0 -> "The location fix isn't usable."
            nowMs - fix.atMs !in -2_000L..MAX_AGE_MS -> "The location fix is too old to identify your current store."
            fix.accuracyMeters?.let { it.isFinite() && it in 0.0..MAX_ACCURACY_METERS } != true -> "The location is too approximate to distinguish nearby stores."
            fix.speedMps?.let { it.isFinite() && it > 2.8 } == true -> "You appear to be moving; passing a store doesn't mean you're shopping there."
            else -> null
        }

        fun resolve(fix: LocationFix?, places: List<Place>, nowMs: Long): StoreEvidence {
            unusable(fix, nowMs)?.let { return Unknown(it) }
            requireNotNull(fix)
            val accuracy = requireNotNull(fix.accuracyMeters)
            val candidates = places.asSequence().filter {
                ShoppingRequest.validTerm(it.name) && it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0
            }.map { it.copy(distanceMeters = haversineMeters(fix.lat, fix.lon, it.lat, it.lon)) }
                .filter { it.distanceMeters!! <= RADIUS_METERS }
                .distinctBy { Triple(it.name.lowercase(), kotlin.math.round(it.lat * 10_000), kotlin.math.round(it.lon * 10_000)) }
                .sortedBy { it.distanceMeters }.toList()
            val first = candidates.firstOrNull() ?: return Unknown("I couldn't find a nearby store in Apple Maps' place data.")
            val distance = first.distanceMeters!!
            val nextDistance = candidates.getOrNull(1)?.distanceMeters ?: Double.POSITIVE_INFINITY
            val close = distance <= maxOf(45.0, accuracy + 20.0)
            val separated = nextDistance - distance > maxOf(40.0, 2 * accuracy)
            return if (accuracy <= 40.0 && close && separated) Likely(first) else Ambiguous(candidates.take(4))
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0; val rad = kotlin.math.PI / 180
            val dLat = (lat2 - lat1) * rad; val dLon = (lon2 - lon1) * rad
            val a = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(lat1 * rad) * kotlin.math.cos(lat2 * rad) * kotlin.math.sin(dLon / 2).let { it * it }
            return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
        }
    }
}
