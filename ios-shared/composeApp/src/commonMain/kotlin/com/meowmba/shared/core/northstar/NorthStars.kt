package com.meowmba.shared.core.northstar

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.TextStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Android NorthStarRuntime on iOS: goals and their evidence links, kept on the phone as JSON
 * instead of Room. Linking is rule-based (Android's Laya pass has no iOS model yet).
 */
class NorthStars(private val storage: TextStorage = DefaultTextStorage, private val clock: () -> Long = PlatformTime::currentMillis) {
    @Serializable private data class Saved(val stars: List<NorthStar> = emptyList(), val links: List<NorthStarLink> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var saved = runCatching { json.decodeFromString<Saved>(storage.readText(KEY) ?: "") }.getOrDefault(Saved())
    private val _views = MutableStateFlow(emptyList<StarView>())
    val views: StateFlow<List<StarView>> = _views
    private val attempted = LinkedHashSet<String>()

    init { refresh() }

    fun stars() = saved.stars.sortedWith(compareByDescending<NorthStar> { it.pinnedAt }.thenBy { it.createdAt })

    fun refresh() { _views.value = stars().map { NorthStarPolicy.view(it, saved.links, clock()) } }

    private fun commit(next: Saved) {
        saved = next.copy(links = next.links.filter { it.at >= clock() - 90 * NorthStarPolicy.DAY }.sortedByDescending { it.at }.take(500))
        storage.writeTextAtomic(KEY, json.encodeToString(Saved.serializer(), saved))
        refresh()
    }

    /** Throws [IllegalArgumentException] with a readable message, like Android. */
    fun save(id: String?, title: String, why: String, horizon: String, ingredients: List<String>): String {
        val now = clock()
        val old = id?.let { i -> saved.stars.firstOrNull { it.id == i } }
        require(title.trim().length in 3..240) { "Give your North Star a name (3–240 characters)." }
        require(old != null || saved.stars.size < 20) { "Keep up to 20 North Stars. Remove one before adding another." }
        val prior = old?.ingredients().orEmpty().associateBy { it.text }
        val clean = ingredients.map { it.trim().take(200) }.filter { it.isNotBlank() }.distinct().take(20).map { prior[it] ?: Ingredient(it) }
        val star = NorthStar(old?.id ?: PlatformId.shortId(), title.trim(), why.trim().take(600), horizon.trim().take(120),
            clean, old?.createdAt ?: now, now, old?.pinnedAt ?: 0)
        // A goal edit can make visible evidence misleading; dismissed tombstones stay.
        val invalidate = old != null && signature(old) != signature(star)
        commit(saved.copy(stars = saved.stars.filter { it.id != star.id } + star,
            links = if (invalidate) saved.links.filter { it.starId != star.id || it.dismissed } else saved.links))
        attempted.clear()
        return star.id
    }

    fun capture(text: String): String? {
        val title = NorthStarPolicy.capture(text)?.replaceFirstChar { it.uppercase() } ?: return null
        if (saved.stars.any { it.title.equals(title, true) }) return null
        return runCatching { save(null, title, "", "Someday", NorthStarPolicy.suggestedIngredients(title).map { it.text }) }.getOrNull()
    }

    fun markIngredient(starId: String, text: String, done: Boolean) {
        commit(saved.copy(stars = saved.stars.map { s ->
            if (s.id != starId) s else s.copy(ingredients = s.ingredients.map {
                if (it.text == text) it.copy(doneAt = if (done) clock() else null) else it }, updatedAt = clock())
        }))
    }

    fun remove(id: String) { commit(Saved(saved.stars.filter { it.id != id }, saved.links.filter { it.starId != id })); attempted.clear() }

    fun dismiss(linkId: String) = commit(saved.copy(links = saved.links.map {
        if (it.id == linkId) it.copy(dismissed = true, sourceKey = "", text = "", reason = "") else it }))

    fun pin(id: String) = commit(saved.copy(stars = saved.stars.map { it.copy(pinnedAt = if (it.id == id) clock() else 0) }))

    /** A finished piece of work the person links by hand (Android linkTask). */
    fun linkResult(starId: String, key: String, title: String, result: String, at: Long) {
        val star = saved.stars.firstOrNull { it.id == starId } ?: return
        val e = StarEvidence(key, title, at)
        val link = NorthStarLink(NorthStarPolicy.linkId(star, e), starId, key, (title + "\n" + result).take(2_000), at,
            "You linked this result. Review its outcome; this does not mark the goal achieved.", "CONNECTION", "owner")
        commit(saved.copy(links = saved.links.filter { it.id != link.id } + link))
    }

    /** Rules pass over local evidence; relevant items become (or stop being) connections. */
    fun ingest(sources: List<StarEvidence>) {
        val now = clock()
        var links = saved.links
        var changed = false
        for (e in sources) for (star in saved.stars) {
            if (e.at < star.createdAt - NorthStarPolicy.DAY || e.at > now + 60_000 || e.text.isBlank()) continue
            val id = NorthStarPolicy.linkId(star, e)
            val revision = id + ":" + star.updatedAt + ":" + e.text.hashCode() + ":" + e.at
            if (!attempted.add(revision)) continue
            while (attempted.size > 4000) attempted.remove(attempted.first())
            val prior = links.firstOrNull { it.id == id }
            if (prior?.dismissed == true) continue
            val match = NorthStarPolicy.match(star, e)
            links = when {
                match == null && prior != null -> links.filter { it.id != id }
                match == null -> continue
                else -> links.filter { it.id != id } + NorthStarLink(id, star.id, e.key, e.text.take(1200), e.at, match.reason, match.kind, match.engine)
            }
            changed = true
        }
        if (changed) commit(saved.copy(links = links)) else refresh()
    }

    /** Title of the goal a Flow source is connected to, for "because → goal". */
    fun goalFor(sourceKey: String): String? = saved.links.firstOrNull { it.sourceKey == sourceKey && !it.dismissed }
        ?.let { l -> saved.stars.firstOrNull { it.id == l.starId }?.title }

    private fun signature(star: NorthStar) = buildString {
        append(star.title.trim().lowercase())
        star.ingredients().map { it.text.trim().lowercase() }.sorted().forEach { append('\u0000').append(it) }
    }

    companion object { const val KEY = "north_stars.json" }
}
