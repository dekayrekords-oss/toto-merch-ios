package com.meowmba.shared.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile

/**
 * Omniroute, running inside the app. Ported from
 * `core/agent/OmniRoute.kt` (android.util.Log replaced by an injectable
 * [log] function so JVM tests do not need Android).
 *
 * The routing decision — which model should answer this — does not need a
 * server to make it. **No request leaves the device to decide where a
 * request should go.**
 *
 * A route is a provider plus what it is good for. Selection is:
 *
 *  1. classify the prompt into one [RouteTag];
 *  2. take the routes carrying that tag, best first;
 *  3. try them in order, falling through on failure to the next.
 *
 * The on-device provider is always last in the chain, so the answer degrades
 * in quality rather than disappearing.
 */
class OmniRoute(
    private val routes: List<Route>,
    /** Where the routing decision is written. Injectable; must never throw. */
    private val log: (String) -> Unit = { },
    /**
     * A route that accepts the request and then never emits is worse than a
     * refused one: skip it and try the next.
     */
    private val firstTokenTimeoutMs: Long = DEFAULT_FIRST_TOKEN_MS,
) : ModelProvider {

    enum class RouteTag { FAST, CHAT, CODING, REASONING, VISION }

    data class Route(
        val id: String,
        val label: String,
        val provider: ModelProvider,
        val tags: Set<RouteTag>,
        /** Lower goes first within a tag. */
        val priority: Int = 50,
        val offline: Boolean = false,
    )

    /**
     * The user's actual words for the next call, when the caller knows them.
     * What reaches [generateStreaming] is an assembled prompt; classifying
     * that is meaningless. The caller sets this to the raw turn.
     */
    @Volatile
    private var userTextHint: String? = null

    @Volatile
    private var forcedTag: RouteTag? = null

    fun hintUserText(text: String?) {
        userTextHint = text?.takeIf { it.isNotBlank() }
    }

    /** Pin the next routing decision to a tag. Consumed by the following [plan]. */
    fun hintTag(tag: RouteTag?) {
        forcedTag = tag
    }

    private val _lastRoute = MutableStateFlow<String?>(null)

    /** Label of the route that most recently answered — shown on the LLM Manager. */
    val lastRoute: StateFlow<String?> = _lastRoute.asStateFlow()

    override val name: String
        get() = "Omniroute (${routes.size} routes)"

    fun classify(prompt: String): RouteTag {
        val text = (userTextHint ?: lastUserTurnOf(prompt)).trim()
        val lower = text.lowercase()

        if (CODE_MARKERS.containsMatchIn(text) ||
            CODING_PHRASES.any { lower.contains(it) }
        ) return RouteTag.CODING

        if (VISION_PHRASES.any { lower.contains(it) }) return RouteTag.VISION

        if (REASONING_PHRASES.any { lower.contains(it) } || text.length > LONG_PROMPT) {
            return RouteTag.REASONING
        }

        val words = lower.split(Regex("\\s+")).count { it.isNotBlank() }
        if (words <= SHORT_PROMPT_WORDS) return RouteTag.FAST

        return RouteTag.CHAT
    }

    /**
     * Best-effort recovery of the last user turn from an assembled prompt.
     * Falls back to the whole prompt rather than guessing wrongly.
     */
    private fun lastUserTurnOf(prompt: String): String {
        for (marker in USER_MARKERS) {
            val idx = prompt.lastIndexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val tail = prompt.substring(idx + marker.length)
                    .substringBefore("<|im_end|>")
                    .substringBefore("<end_of_turn>")
                    .trim()
                if (tail.isNotEmpty()) return tail
            }
        }
        return prompt
    }

    /**
     * The order this prompt should be attempted in, best first, fallbacks after.
     * [preferRouteId] pins one route to the front regardless of classification.
     */
    fun plan(prompt: String, preferRouteId: String? = null, forceTag: RouteTag? = null): List<Route> {
        val tag = forceTag ?: run {
            val hinted = forcedTag
            forcedTag = null
            hinted
        } ?: classify(prompt)
        val tagged = routes.filter { tag in it.tags }.sortedBy { it.priority }
        val rest = routes.filterNot { it in tagged }
            // Offline last: it is the safety net, not the preference.
            .sortedWith(compareBy({ it.offline }, { it.priority }))
        val planned = tagged + rest
        val pinned = preferRouteId?.let { id -> planned.firstOrNull { it.id == id } }
        return if (pinned != null) listOf(pinned) + (planned - pinned) else planned
    }

    override suspend fun generate(prompt: String): ModelResponse {
        val plan = plan(prompt)
        if (plan.isEmpty()) {
            return ModelResponse.Error("No models are configured for Omniroute to route to.")
        }
        var lastError: String? = null
        for (route in plan) {
            val response = try { route.provider.generate(prompt) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { ModelResponse.Error(error.message ?: error::class.simpleName ?: "error") }
            when (response) {
                is ModelResponse.Error -> lastError = "${route.label}: ${response.message}"
                else -> {
                    win(route, prompt)
                    return response
                }
            }
        }
        log("every route failed; last error: ${lastError ?: "unknown"}")
        return ModelResponse.Error(
            "Every route failed. Last was ${lastError ?: "unknown"}."
        )
    }

    override fun generateStreaming(prompt: String): Flow<ModelResponse> =
        generateStreaming(prompt, preferRouteId = null)

    /**
     * Streaming with fallback. A route only counts as failed if it fails
     * *before producing anything*; once text has been emitted, silently
     * restarting on a different model would rewrite what the user is reading.
     */
    fun generateStreaming(
        prompt: String,
        preferRouteId: String?,
        forceTag: RouteTag? = null,
    ): Flow<ModelResponse> = flow {
        val plan = plan(prompt, preferRouteId, forceTag)
        if (plan.isEmpty()) {
            emit(ModelResponse.Error("No models are configured for Omniroute to route to."))
            return@flow
        }
        var lastError: String? = null
        for (route in plan) {
            var produced = false
            log("trying ${route.id} (${route.label})")
            val failure = runCatching {
                coroutineScope {
                    val inbox = Channel<ModelResponse>(Channel.UNLIMITED)
                    val pump = launch {
                        try {
                            route.provider.generateStreaming(prompt).collect { inbox.send(it) }
                        } catch (cancelled: CancellationException) {
                            inbox.close(cancelled)
                            throw cancelled
                        } finally {
                            inbox.close()
                        }
                    }
                    try {
                        suspend fun handle(response: ModelResponse, alreadyProduced: Boolean): Boolean {
                            when (response) {
                                is ModelResponse.Error -> {
                                    if (alreadyProduced) emit(response) else {
                                        lastError = "${route.label}: ${response.message}"
                                    }
                                    return alreadyProduced
                                }
                                is ModelResponse.Partial -> {
                                    if (!alreadyProduced) win(route, prompt)
                                    emit(response)
                                    return true
                                }
                                is ModelResponse.Complete -> {
                                    if (!alreadyProduced) win(route, prompt)
                                    emit(response)
                                    return true
                                }
                            }
                        }
                        val received = withTimeoutOrNull(firstTokenTimeoutMs.coerceAtLeast(1L)) {
                            inbox.receiveCatching()
                        }
                        received?.exceptionOrNull()?.let { throw it }
                        val first = received?.getOrNull()
                        if (first == null) {
                            lastError = "${route.label}: no tokens in ${firstTokenTimeoutMs}ms"
                            log(lastError!!)
                            return@coroutineScope
                        }
                        if (!handle(first, alreadyProduced = false)) return@coroutineScope
                        produced = true
                        for (next in inbox) handle(next, alreadyProduced = true)
                    } finally {
                        pump.cancel()
                    }
                }
            }.exceptionOrNull()

            if (failure is CancellationException) throw failure
            if (produced) return@flow
            if (failure != null && failure !is CancellationException) {
                lastError = "${route.label}: ${failure.message ?: failure::class.simpleName ?: "error"}"
            }
        }
        log("every route failed; last error: ${lastError ?: "unknown"}")
        emit(ModelResponse.Error("Every route failed. Last was ${lastError ?: "unknown"}."))
    }

    /** Records which route answered. */
    private fun win(route: Route, prompt: String) {
        _lastRoute.value = route.label
        log("routed ${classify(prompt)} -> ${route.id} (${route.label})")
    }

    private companion object {
        /** How the prompt builders and chat templates in this app mark a user turn. */
        val USER_MARKERS = listOf(
            "<|im_start|>user\n", "<start_of_turn>user\n", "\nUser:", "\nuser:",
        )

        /** Backticks, braces, obvious declarations — anything that reads as source. */
        val CODE_MARKERS = Regex("```|\\bfun \\w|\\bdef \\w|\\bclass \\w|#include|=>|\\{\\s*\\n")

        val CODING_PHRASES = listOf(
            "write a function", "write code", "refactor", "debug", "stack trace",
            "compile", "unit test", "regex", "sql query", "kotlin", "python",
            "javascript", "typescript", "why does this code", "fix this code",
        )

        val VISION_PHRASES = listOf(
            "what do you see", "look at this", "describe this photo",
            "describe the picture", "read this label", "what is in this image",
        )

        val REASONING_PHRASES = listOf(
            "explain why", "compare", "trade-off", "tradeoff", "step by step",
            "plan for", "strategy", "pros and cons", "analyse", "analyze",
            "work out", "figure out why",
        )

        const val LONG_PROMPT = 320
        const val SHORT_PROMPT_WORDS = 6
        const val DEFAULT_FIRST_TOKEN_MS = 20_000L
    }
}
