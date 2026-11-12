package com.meowmba.shared.agent.runtime

import com.meowmba.shared.agent.ModelProvider
import com.meowmba.shared.agent.ModelResponse
import com.meowmba.shared.agent.runtime.ToolCallPrompt.ToolCallPromptMode
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.voice.VoiceText
import com.meowmba.shared.voice.WakePhraseMatcher

/**
 * Routing core (ported from `core/agent/runtime/LlmToolCallRouter.kt`,
 * unchanged in behavior). The keyword fast path, command heuristics, and the
 * model-based tool decision all live here; the platform supplies the
 * [ModelProvider] (network or on-device).
 */

/** Cheap pre-filter: only spend a model call on text that smells like a tool
 *  command. Keyword commands never reach the model at all (zero-latency fast
 *  path), and plain conversation never triggers a routing roundtrip. */
object CommandHeuristics {
    private val SIGNAL_WORDS = setOf(
        // Files, projects, memory
        "create", "make", "file", "files", "assets", "library", "organize", "cleanup", "clean", "folder", "project", "delete", "remove",
        "read", "open", "write", "save", "search", "find", "list", "show",
        "task", "workspace", "memory", "remember", "note", "scaffold", "todo",
        // Email and calendar
        "email", "mail", "send", "draft", "reply", "compose", "inbox",
        "calendar", "event", "schedule", "meeting", "appointment", "remind",
        // Media generation
        "image", "picture", "photo", "draw", "paint", "sketch", "render",
        "generate", "video", "animation", "animate", "song", "audio", "sound",
        // Phone and desktop control
        "app", "launch", "start", "call", "text", "settings", "camera",
        "run", "script", "build", "desktop", "computer",
        // Phone control (accessibility-based)
        "tap", "click", "press", "swipe", "scroll", "type", "back",
        "control", "navigate", "play", "pause", "stop", "next", "previous",
        // Avatar performance + follow-up affirmations
        "trick", "dance", "perform", "wiggle", "paw", "lick", "gesture",
        "yes", "yeah", "yep", "sure", "ok", "okay", "please",
    )

    fun mightBeCommand(text: String): Boolean {
        if (AvatarFormRequest.parse(VoiceText.stripAddress(text)) != null) return true
        val normalized = WakePhraseMatcher.normalize(text)
        if (normalized.matches(Regex("(yes|yeah|yep|sure|ok|okay)( please)?"))) return true
        val request = normalized
            .replace(Regex("^(hey|hi)\\s+"), "")
            .replace(Regex("^(please|just)\\s+"), "")
            .replace(Regex("^(can|could|would|will) you\\s+"), "")
            .replace(Regex("^(i want you to|i'd like you to|i would like you to|let's|lets)\\s+"), "")
            .replace(Regex("^please\\s+"), "")
        // A noun mentioned in conversation is not a request to act on it.
        val first = request.substringBefore(' ')
        return first in ACTION_VERBS
    }

    private val ACTION_VERBS = setOf(
        "create", "make", "organize", "clean", "delete", "remove", "read", "open",
        "write", "save", "search", "find", "list", "show", "remember", "note",
        "scaffold", "email", "mail", "send", "draft", "reply", "compose", "schedule",
        "remind", "draw", "paint", "sketch", "render", "generate", "animate", "launch",
        "start", "call", "text", "run", "build", "tap", "click", "press", "swipe",
        "scroll", "type", "control", "navigate", "play", "pause", "stop", "perform",
        "dance", "wiggle", "lick", "do", "set", "turn", "change", "update", "move", "work", "plan",
    )
}

/**
 * Adapter seam for agent-brain backends (the spec's "don't couple the UI to
 * one agent framework" rule). Implementations: [LlmToolCallRouter],
 * platform model clients, or test fakes.
 */
interface AgentBrainRouter {
    suspend fun route(text: String, registry: ToolRegistry): AgentRouter.AgentIntent?
}

/**
 * The on-device agent brain.
 *
 * Routing order:
 *  1. Keyword fast path (free, deterministic).
 *  2. Command heuristic gate (free; skips the model for chit-chat).
 *  3. The active [ModelProvider] renders a single-JSON tool decision
 *     (prompt contract + defensive parse).
 *
 * The model only ever PROPOSES. The orchestrator's permission checks and
 * the user's approval card still decide what actually runs.
 */
class LlmToolCallRouter(
    private val provider: ModelProvider,
    private val toolRegistry: ToolRegistry,
    private val keywordRouter: (String) -> AgentRouter.AgentIntent? = AgentRouter::route,
    private val promptMode: ToolCallPromptMode = ToolCallPromptMode.CONTRACT
) {

    suspend fun route(text: String, context: String? = null): AgentRouter.AgentIntent? {
        // 1. Zero-latency keyword path
        val kwResult = keywordRouter(text)
        kwResult?.let { return it }

        // 2. Don't spend a model call on obvious conversation
        if (!CommandHeuristics.mightBeCommand(text)) return null

        // 3. Model-based decision
        val prompt = when (promptMode) {
            ToolCallPromptMode.CONTRACT -> ToolCallPrompt.build(text, toolRegistry.definitions(), context)
            ToolCallPromptMode.HERMES_NATIVE -> ToolCallPrompt.buildHermesNative(text, toolRegistry.definitions(), context)
        }
        val response = try {
            provider.generate(prompt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val output = (response as? ModelResponse.Complete)?.text ?: return null

        // Parse must never be able to crash the app: the model's output is
        // untrusted by contract ("garbage yields null").
        val call = runCatching { ToolCallParser.parse(output, toolRegistry) }
            .getOrNull() ?: return null

        // Resolve which available agent owns this tool
        val owner = AgentRegistry.ownerOf(call.toolId) ?: return null

        return AgentRouter.AgentIntent(
            agentId = owner.id,
            toolId = call.toolId,
            args = call.args,
            title = describe(call)
        )
    }

    private fun describe(call: ToolCallParser.ParsedToolCall): String {
        val tool = toolRegistry.byId(call.toolId)
        val keyArg = call.args.values.firstOrNull()?.take(40)
        return listOfNotNull(tool?.definition?.displayName, keyArg).joinToString(": ")
            .ifBlank { call.toolId }
    }
}
