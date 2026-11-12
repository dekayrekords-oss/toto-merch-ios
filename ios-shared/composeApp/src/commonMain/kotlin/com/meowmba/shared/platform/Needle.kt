package com.meowmba.shared.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cactus Needle 3 (the same pinned release Android uses): a 35 MB on-device model that only picks
 * tools. iOS host: iosApp/NeedleRuntime.swift. It proposes; [DeviceActions] and the app still act.
 */
interface NeedleDriver {
    /** "missing", "downloading", "ready" or "failed: reason". */
    fun status(): String
    fun download(onProgress: (Float) -> Unit, onDone: (ok: Boolean, message: String) -> Unit)
    fun delete()
    /** Runs one turn; [onResult] gets Needle's JSON or null, on the main thread. */
    fun route(systemPrompt: String, toolsJson: String, input: String, onResult: (String?) -> Unit)
}

object Needle {
    var driver: NeedleDriver? = null
        set(value) { field = value; refresh() }
    val status = MutableStateFlow("missing")
    val enabled = MutableStateFlow(com.meowmba.shared.core.DefaultTextStorage.readText("needle_enabled") != "false")
    fun refresh() { status.value = driver?.status() ?: "unavailable" }
    fun setEnabled(on: Boolean) { enabled.value = on; com.meowmba.shared.core.DefaultTextStorage.writeTextAtomic("needle_enabled", on.toString()) }
    fun ready() = enabled.value && driver != null && status.value == "ready"

    const val SYSTEM_PROMPT = "Choose a tool only when the user clearly requests an action. Refuse ordinary conversation."

    private fun tool(name: String, description: String, vararg params: Pair<String, String>) =
        """{"name":"$name","description":"$description","parameters":{"type":"object","properties":{""" +
            params.joinToString(",") { (p, d) -> """"$p":{"type":"string","description":"$d"}""" } + "}}}"

    val toolsJson = "[" + listOf(
        tool("open_app", "Open an app on the iPhone.", "app" to "App name, e.g. Maps, Spotify"),
        tool("run_shortcut", "Run one of the user's Siri Shortcuts by name.", "name" to "Shortcut name"),
        tool("create_reminder", "Create a reminder.", "title" to "What to be reminded about", "when" to "When, in the user's words, e.g. tomorrow at 5pm"),
        tool("add_calendar_event", "Add an event to the calendar.", "title" to "Event title", "when" to "When, in the user's words"),
        tool("read_calendar", "Say what is on the calendar.", "day" to "today or tomorrow"),
        tool("send_email", "Write an email for the user to send.", "to" to "Email address", "subject" to "Subject", "body" to "Message"),
        tool("send_text", "Write a text message for the user to send.", "to" to "Phone number", "body" to "Message"),
    ).joinToString(",") + "]"

    /** Cheap gate so ordinary chat never waits on the router. */
    fun mightBeCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length < 4 || t.length > 240 || t.endsWith("?") && !t.startsWith("can you") && !t.startsWith("could you") && !t.startsWith("what's on") && !t.startsWith("whats on")) return false
        return Regex("\\b(open|launch|start|run|remind|reminder|schedule|calendar|add|put|book|email|e-mail|mail|text|message|send|play|navigate|shortcut|ping|tell|let|call|note|draft|write|set|remember to|book|show)\\b").containsMatchIn(t)
    }

    /** Needle's reply → an action the app can run, or null to let Meow chat instead. */
    fun toAction(raw: String?, now: Long, offset: Int): DeviceAction? {
        val root = runCatching { Json.parseToJsonElement(raw ?: return null).jsonObject }.getOrNull() ?: return null
        // Needle reports a calibrated confidence; below half, let Meow talk it through instead.
        val confidence = runCatching { root["confidence"]?.jsonPrimitive?.contentOrNull?.toDouble() }.getOrNull() ?: 1.0
        if (confidence < 0.5) return null
        val call = runCatching { root["function_calls"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull() ?: return null
        val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val args = runCatching { call["arguments"]?.jsonObject }.getOrNull()
            ?.mapValues { (_, v) -> ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()).trim() }.orEmpty()
        fun a(k: String) = args[k].orEmpty()
        return when (name) {
            "open_app" -> IosApps.match("open " + a("app"))?.let { DeviceAction.OpenApp(it) }
            "run_shortcut" -> a("name").takeIf { it.isNotBlank() }?.let { DeviceAction.RunShortcut(it) }
            "create_reminder" -> a("title").takeIf { it.isNotBlank() }?.let {
                val due = if (a("when").isBlank()) -1L else DeviceActions.splitWhen(a("when"), now, offset).second
                DeviceAction.Remind(it, due)
            }
            "add_calendar_event" -> a("title").takeIf { it.isNotBlank() }?.let {
                val at = DeviceActions.splitWhen(a("when"), now, offset).second
                DeviceAction.AddEvent(it, if (at > 0) at else (now / 3_600_000L + 1) * 3_600_000L)
            }
            "read_calendar" -> DeviceAction.ReadCalendar(a("day").contains("tomorrow", true))
            "send_email" -> a("to").takeIf { '@' in it }?.let { DeviceAction.Email(it, a("subject"), a("body")) }
            "send_text" -> a("to").filter { it.isDigit() || it == '+' }.takeIf { it.length >= 5 }?.let { DeviceAction.Text(it, a("body")) }
            else -> null
        }
    }
}
