package com.meowmba.shared.core.shopping

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** iOS: one precise location fix plus Apple Maps stores nearby. Nothing is kept afterwards. */
interface PlacesDriver {
    /** [fix] is "lat|lon|accuracyMeters|atMs|speedMps"; [places] are "name|lat|lon". */
    fun nearbyStores(onResult: (fix: String?, places: List<String>, error: String?) -> Unit)
}

/** Android WebSearchTool.search: keyless engines in parallel; a blocked engine never sinks the rest. */
class KeylessSearchClient(private val client: HttpClient = HttpClient()) {
    suspend fun search(query: String, engines: List<String> = listOf("ddg_lite", "ddg_html", "bing", "brave", "mojeek")): List<WebHit> = coroutineScope {
        engines.map { engine ->
            async {
                val url = KeylessWebSearch.searchUrl(engine, query) ?: return@async emptyList<WebHit>()
                withTimeoutOrNull(10_000) {
                    runCatching { KeylessWebSearch.parse(engine, client.get(url) { header("User-Agent", "Mozilla/5.0 (MeowmbaBot/1.0)") }.bodyAsText()) }.getOrNull()
                }.orEmpty()
            }
        }.awaitAll().flatten().let(KeylessWebSearch::fuse)
    }
}

/** Android StoreShoppingTool (build 177): on request only; no background checks or purchases. */
class StoreShopping(
    private val search: suspend (String) -> List<WebHit>,
    private val nearby: suspend () -> Pair<LocationFix?, List<Place>>?,
    private val nowMs: () -> Long,
    private val today: () -> DealEvidence.Day,
) {
    suspend fun run(request: ShoppingRequest): String {
        val store = request.store ?: return identifyStore(request)
        val date = today()
        val hits = runCatching { search(DealEvidence.query(store, request.item, date)) }.getOrNull()
            ?: return "I couldn't check deal sources for $store online right now. I haven't verified any current offers."
        val leads = DealEvidence.select(store, request.item, hits, date)
        if (leads.isEmpty()) return "I couldn't find usable deal evidence at $store${request.item?.let { " for $it" }.orEmpty()}. Search may be blocked or incomplete; that doesn't mean the store has no deals."
        return buildString {
            append("I found ${leads.size} deal sources for $store${request.item?.let { " for $it" }.orEmpty()}; details and links are below. These aren't verified checkout prices.")
            append("\n\nDeal leads searched $date — retailer sources first, not ranked by guaranteed savings. Search excerpts may be stale or incomplete. Check dates, local availability, sizes, minimum spend, coupon and membership terms before buying.")
            leads.forEachIndexed { index, lead ->
                append("\n\n${index + 1}. ${lead.title} (${if (lead.official) "retailer website" else "third-party source; verify with the store"})")
                if (lead.excerpt.isNotBlank()) append("\nSearch excerpt (unverified): ${lead.excerpt}")
                append("\n${lead.url}")
            }
            if (request.item == null) append("\n\nFor a focused search, say ‘deals on coffee at $store’. I haven't checked your cart, store app, loyalty account or checkout price.")
        }
    }

    private suspend fun identifyStore(request: ShoppingRequest): String {
        val found = runCatching { nearby() }.getOrNull()
            ?: return "The nearby-store lookup didn't finish. Tell me the store name, for example ‘deals at Target’, and I can still search for deals."
        val (fix, places) = found
        StoreEvidence.unusable(fix, nowMs())?.let { return "$it Tell me the store name, for example ‘deals at Target’, or check Meowmba's location permission and try again." }
        return when (val e = StoreEvidence.resolve(fix, places, nowMs())) {
            is StoreEvidence.Unknown -> "${e.reason} Tell me the store name, for example ‘deals at Target’."
            is StoreEvidence.Ambiguous -> "I can't reliably tell which store you're in. Nearby options are " +
                e.candidates.joinToString(", ") { it.name } + ". Say ‘deals at’ followed by the right store name. I haven't searched offers for an unconfirmed store."
            is StoreEvidence.Likely -> "Location suggests ${e.place.name} nearby; I can't confirm you're inside. " +
                "For offers, say ‘deals${request.item?.let { " on $it" }.orEmpty()} at ${e.place.name}’. " +
                "That shares the named store with public search engines, not your location. I haven't shared the inferred store name with them."
        }
    }

    companion object {
        fun parseNearby(fix: String?, places: List<String>): Pair<LocationFix?, List<Place>> {
            val f = fix?.split('|')?.takeIf { it.size >= 4 }?.let {
                LocationFix(it[0].toDouble(), it[1].toDouble(), it[2].toDoubleOrNull()?.takeIf { a -> a >= 0 }, it[3].toLong(), it.getOrNull(4)?.toDoubleOrNull()?.takeIf { s -> s >= 0 })
            }
            return f to places.mapNotNull { l -> l.split('|').takeIf { it.size == 3 }?.let { Place(it[0], it[1].toDouble(), it[2].toDouble()) } }
        }
    }
}
