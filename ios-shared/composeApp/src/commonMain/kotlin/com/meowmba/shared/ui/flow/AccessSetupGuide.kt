package com.meowmba.shared.ui.flow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.platform.PlatformDrivers

/**
 * Android AccessSetupGuide (build 178) for iOS. Every step is optional and approved in iOS's own
 * prompts; the guide rechecks real state and never runs a task.
 */
@Composable
internal fun AccessSetupGuide(onClose: () -> Unit) {
    val cal = PlatformDrivers.calendar
    var calendar by remember { mutableStateOf(cal?.hasAccess() == true) }
    var message by remember { mutableStateOf<String?>(null) }
    val needle by com.meowmba.shared.platform.Needle.status.collectAsState()
    fun open(url: String) = PlatformDrivers.apps?.open(url) { ok ->
        if (!ok) message = "That screen couldn't be opened. Open the Settings app and find Meowmba."
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Connect apps & tools", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Done") }
        }
        Text("Choose only what you want Meow to use. You approve each iPhone prompt yourself and can revoke access later in Settings › Meowmba.")

        Text("1 · Calendar & Reminders", style = MaterialTheme.typography.titleMedium)
        Text(if (calendar) "Ready to read and change calendars and reminders" else "Not enabled")
        Text("Flow shows your next event and reminders due today. In Flow, Comment → ‘this event is canceled’ → Cancel this occurrence changes only that event and checks the result.")
        Button(enabled = !calendar && cal != null, onClick = {
            cal?.requestAccess { granted, reason -> calendar = granted; message = if (granted) "Calendar and Reminders are connected." else reason }
        }) { Text("Allow calendar access") }
        OutlinedButton(onClick = { open("app-settings:") }) { Text("App permissions / denied access") }
        HorizontalDivider()

        Text("2 · Mail and messages", style = MaterialTheme.typography.titleMedium)
        Text("iOS doesn't let any app read your inbox or other apps' notifications. Meow prepares drafts in Mail, Gmail or Messages; you review them and tap Send.")
        OutlinedButton(onClick = { open("message://") }) { Text("Open Mail") }
        HorizontalDivider()

        Text("3 · Location", style = MaterialTheme.typography.titleMedium)
        Text("Used only when you ask about weather or places, or when the weather background is on.")
        OutlinedButton(onClick = {
            PlatformDrivers.location?.requestFix({ _, _ -> message = "Location works." }, { message = it })
        }) { Text("Allow location") }
        HorizontalDivider()

        Text("4 · Siri, Shortcuts and Back Tap", style = MaterialTheme.typography.titleMedium)
        Text("Say “Ask Meowmba…”, or add “Ask Meow” and “Ask Meow about this screen” to the Action Button or Back Tap. Shortcuts is also how Meow runs your own automations: say “run my shortcut …”.")
        OutlinedButton(onClick = { open("shortcuts://") }) { Text("Open Shortcuts") }
        HorizontalDivider()

        Text("5 · Phone commands (Needle 3)", style = MaterialTheme.typography.titleMedium)
        Text(when {
            needle == "ready" -> "Needle 3 is on this phone"
            needle.startsWith("failed") -> "Needle 3 download failed — retry in LLM"
            else -> "Not downloaded — Menu › LLM › Needle 3"
        })
        Text("A 35 MB on-device model that turns loosely worded requests into reminders, events, drafts, shortcuts and apps.")
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        TextButton(onClick = { calendar = cal?.hasAccess() == true; message = "Access status refreshed. No task was run." }) { Text("Check access again") }
    }
}
