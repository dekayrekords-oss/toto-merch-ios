package com.meowmba.shared.ui.vending

import com.meowmba.shared.agent.ModelProvider
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.TextStorage
import com.meowmba.shared.orgjson.JSONArray
import com.meowmba.shared.orgjson.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android GeneratedAvatarStore / GeneratedPropStore on iOS: one bounded, validated library file
 * per kind. Every saved entry is re-parsed on load; its id must match its contents.
 */
internal class FormLibrary<T>(
    private val key: String,
    private val max: Int,
    private val parse: (String) -> T,
    private val toJson: (T) -> String,
    private val idOf: (T) -> String,
    private val nameOf: (T) -> String,
    private val descriptionOf: (T) -> String,
    private val storage: TextStorage = DefaultTextStorage,
) {
    private class Saved<T>(val form: T, val requests: List<String>)

    private fun read(): List<Saved<T>> = runCatching {
        val root = JSONObject(storage.readText(key) ?: return emptyList())
        if (root.optInt("version") != 1) return emptyList()
        val items = root.getJSONArray("items")
        (0 until items.length()).mapNotNull { i ->
            runCatching {
                val o = items.optJSONObject(i)!!
                val form = parse(o.getJSONObject("form").toString())
                val reqs = o.getJSONArray("requests")
                Saved(form, (0 until reqs.length()).mapNotNull { reqs.opt(it) as? String }.map(::normalize))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<Saved<T>>) {
        val arr = JSONArray()
        items.forEach { s -> arr.put(JSONObject().put("form", JSONObject(toJson(s.form))).put("requests", JSONArray(s.requests))) }
        storage.writeTextAtomic(key, JSONObject().put("version", 1).put("items", arr).toString())
    }

    fun list(): List<T> = read().map { it.form }.take(max)
    fun load(id: String): T? = read().firstOrNull { idOf(it.form) == id }?.form
    fun find(request: String): T? {
        val n = normalize(request); if (n.isEmpty()) return null
        return read().firstOrNull { n in it.requests || n == normalize(nameOf(it.form)) || n == normalize(descriptionOf(it.form)) }?.form
    }

    fun save(form: T, request: String) {
        val validated = parse(toJson(form))
        require(idOf(form) == idOf(validated)) { "Drawing ID does not match its contents" }
        val n = normalize(request); require(n.isNotEmpty()) { "Describe it to save" }
        val items = read()
        val previous = items.firstOrNull { idOf(it.form) == idOf(form) }
        // Never evict behind the user's back: it may still be worn.
        if (previous == null && items.size >= max) throw IllegalStateException("Your library is full. Remove one before creating another.")
        val requests = (listOf(n) + previous?.requests.orEmpty()).distinct().take(8)
        write(listOf(Saved(validated, requests)) + items.filter { idOf(it.form) != idOf(form) })
    }

    fun replace(oldId: String, form: T, request: String) { if (oldId != idOf(form)) delete(oldId); save(form, request) }
    fun delete(id: String) = write(read().filter { idOf(it.form) != id })

    companion object {
        fun normalize(request: String) = request.lowercase().trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?')
    }
}

/**
 * The Vending Machine's state (Android CompanionViewModel invent paths): worn form, equipped prop,
 * libraries, and at most one avatar and one prop invention at a time.
 */
object Vending {
    /** The configured AI; set by the companion view model. */
    var provider: (() -> ModelProvider?)? = null
    /** Local inference gets the shorter budgets (Android InventTimeBudget). */
    var providerIsRemote: () -> Boolean = { true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val avatars = FormLibrary("vending_avatars.json", GeneratedAvatarForms.MAX_SAVED, GeneratedAvatarForms::parse,
        GeneratedAvatarForms::toJson, { it.id }, { it.name }, { it.description })
    internal val props = FormLibrary("vending_props.json", GeneratedPropForms.MAX_SAVED, GeneratedPropForms::parse,
        GeneratedPropForms::toJson, { it.id }, { it.name }, { it.description })

    val worn = MutableStateFlow(DefaultTextStorage.readText("vending_worn")?.let { avatars.load(it) })
    val savedForms = MutableStateFlow(avatars.list())
    val equipped = MutableStateFlow(DefaultTextStorage.readText("vending_equipped")?.let { props.load(it) })
    val savedProps = MutableStateFlow(props.list())
    val avatarBusy = MutableStateFlow(false)
    val avatarMessage = MutableStateFlow<Pair<String, Boolean>?>(null)
    val propBusy = MutableStateFlow(false)
    val propMessage = MutableStateFlow<Pair<String, Boolean>?>(null)
    val placing = MutableStateFlow<GeneratedPropForm?>(null)
    private var avatarJob: Job? = null
    private var propJob: Job? = null

    private fun generator(): (suspend (String) -> ModelResponse)? {
        val p = provider?.invoke() ?: return null
        return { prompt -> p.generate(prompt) }
    }

    fun wear(form: GeneratedAvatarForm?) {
        worn.value = form
        if (form == null) DefaultTextStorage.delete("vending_worn") else DefaultTextStorage.writeTextAtomic("vending_worn", form.id)
    }
    /** Android's QA example forms: wearable offline, no AI needed. */
    fun wearExample(form: GeneratedAvatarForm) {
        runCatching { avatars.save(form, form.name) }
        savedForms.value = avatars.list(); wear(form)
        avatarMessage.value = "Wearing ${form.name}." to false
    }
    fun select(id: String) { avatars.load(id)?.let { wear(it); avatarMessage.value = "Wearing ${it.name}." to false } }
    fun deleteForm(id: String) {
        avatars.delete(id); savedForms.value = avatars.list()
        if (worn.value?.id == id) wear(null)
    }

    fun equip(id: String?) {
        val prop = id?.let { props.load(it) }
        equipped.value = prop
        if (prop == null) DefaultTextStorage.delete("vending_equipped") else DefaultTextStorage.writeTextAtomic("vending_equipped", prop.id)
        propMessage.value = (prop?.let { "Using ${it.name}." } ?: "Prop put away.") to false
    }
    fun deleteProp(id: String) {
        props.delete(id); savedProps.value = props.list()
        if (equipped.value?.id == id) equip(null)
    }
    fun savePlacement(old: GeneratedPropForm, placed: GeneratedPropForm) {
        runCatching { props.replace(old.id, placed, placed.name) }
        savedProps.value = props.list()
        if (equipped.value?.id == old.id) equip(placed.id)
        placing.value = null
        propMessage.value = "Saved placement for ${placed.name}." to false
    }

    fun cancelAvatar() { avatarJob?.cancel(); avatarBusy.value = false; avatarMessage.value = "Creation cancelled. Your current look stays." to false }
    fun cancelProp() { propJob?.cancel(); propBusy.value = false; propMessage.value = "Prop dispensing cancelled." to false }

    /** "Become anything": invent a new form, or refine the worn one ([refine] = true). */
    fun createAvatar(description: String, level: InventReasonLevel = InventReasonLevel.BALANCED, refine: Boolean = false) {
        val request = description.trim()
        avatarJob?.cancel()
        if (!refine) avatars.find(request)?.let { saved -> wear(saved); avatarMessage.value = "Wearing saved form ${saved.name}." to false; return }
        val generate = generator() ?: run { avatarMessage.value = "Choose a working AI model in LLM settings to create forms." to true; return }
        val current = worn.value
        if (refine && current == null) { avatarMessage.value = "Wear a generated form before refining it." to true; return }
        avatarBusy.value = true
        avatarMessage.value = (if (refine) "Refining ${current!!.name}…" else "Creating your form…") to false
        avatarJob = scope.launch {
            val inventor = AvatarFormInventor(generate)
            val progress = launch {
                var s = 0
                while (true) { delay(5_000); s += 5
                    avatarMessage.value = "Drawing and checking your form · ${s}s. Free services may queue requests. Cancel anytime." to false }
            }
            try {
                val form = withTimeout(InventTimeBudget.avatar(providerIsRemote(), level)) {
                    if (refine) inventor.refine(current!!, request, level) else inventor.invent(request, level)
                }
                avatars.save(form, request)
                savedForms.value = avatars.list()
                wear(form)
                avatarMessage.value = ("Now wearing ${form.name}." + (inventor.completionNotice?.let { " $it" } ?: "")) to false
            } catch (e: TimeoutCancellationException) {
                val kept = inventor.lastValidated
                if (kept != null) { runCatching { avatars.save(kept, request) }; savedForms.value = avatars.list(); wear(kept)
                    avatarMessage.value = "Time ran out during polish; wearing the completed design ${kept.name}." to false }
                else avatarMessage.value = "That took too long. Try Faster, or a simpler description." to true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                avatarMessage.value = (e.message?.take(240) ?: "Couldn't create that form.") to true
            } finally { progress.cancel(); avatarBusy.value = false }
        }
    }

    fun inventProp(prompt: String, category: String, detail: String, level: InventReasonLevel = InventReasonLevel.BALANCED) {
        val request = prompt.trim()
        propJob?.cancel()
        props.find(request)?.let { saved -> placing.value = saved; propMessage.value = "Already in library: ${saved.name}. Adjust placement?" to false; return }
        val generate = generator() ?: run { propMessage.value = "Choose a working AI model in LLM settings to invent props." to true; return }
        propBusy.value = true
        propMessage.value = "Dispensing your prop…" to false
        propJob = scope.launch {
            val inventor = PropInventor(generate)
            val progress = launch {
                var s = 0
                while (true) { delay(5_000); s += 5
                    propMessage.value = "Creating and checking your prop · ${s}s. Free services may queue requests. Cancel anytime." to false }
            }
            try {
                val raw = withTimeout(InventTimeBudget.prop(providerIsRemote(), level)) { inventor.invent(request, level, category, detail) }
                val form = GeneratedPropForms.withSmartDefaults(raw)
                props.save(form, request)
                savedProps.value = props.list()
                placing.value = form
                propMessage.value = "Dispensed ${form.name}. Place it on your avatar, then Use it." to false
            } catch (e: TimeoutCancellationException) {
                propMessage.value = "That took too long. Try Faster, or a simpler prop." to true
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                propMessage.value = (e.message?.take(240) ?: "Couldn't dispense that prop.") to true
            } finally { progress.cancel(); propBusy.value = false }
        }
    }
}
