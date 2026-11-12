package com.meowmba.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Android AgentsScreen for iOS: what Meow can use right now, with honest availability
 * ("ready" = installed and connected, not proof that an action succeeded), and the saved work.
 */
@Composable
internal fun AgentsScreen(onBack: () -> Unit, onOpen: (String) -> Unit, onWork: () -> Unit) {
    val needle by com.meowmba.shared.platform.Needle.status.collectAsState()
    val desktop by com.meowmba.shared.bridge.DesktopBridge.paired.collectAsState()
    val servers by com.meowmba.shared.computer.SshProfiles.all.collectAsState()
    val robot by com.meowmba.shared.robot.Cozmo.state.collectAsState()
    val forms by com.meowmba.shared.ui.vending.Vending.savedForms.collectAsState()
    val cal = com.meowmba.shared.platform.PlatformDrivers.calendar?.hasAccess() == true
    data class Agent(val name: String, val purpose: String, val ready: Boolean?, val status: String, val open: String?)
    val agents = listOf(
        Agent("Phone", "Reminders, calendar, drafts in Mail and Messages, apps and Shortcuts.", cal,
            if (cal) "Calendar & Reminders connected" else "Calendar access not granted yet", "Phone & device"),
        Agent("Needle 3 router", "Turns loosely worded requests into phone actions, on the phone.", needle == "ready",
            if (needle == "ready") "Downloaded" else "Not downloaded", "LLM"),
        Agent("Terminal", "A shell on your own servers over SSH.", servers.isNotEmpty(),
            if (servers.isEmpty()) "No servers added" else "${servers.size} server(s)", "Terminal"),
        Agent("Shopping", "Which store you're in and live deal sources, only when you ask.", true, "Ready (web search, Apple Maps)", null),
        Agent("Cozmo", "Head, lift, moves, lights and camera for your Cozmo robot.", robot.connected,
            if (robot.connected) "Connected" else "Not connected", "Robot"),
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Agents & Tools", style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = onWork) { Text("Meow’s work · saved results") }
            Text("Ready means connected and allowed, not that a particular action worked — check each result in Meow’s work.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            agents.forEach { a ->
                Surface(Modifier.fillMaxWidth().let { m -> a.open?.let { t -> m.clickable { onOpen(t) } } ?: m },
                    color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            val color = if (a.ready == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            Surface(color = color.copy(alpha = .15f), shape = RoundedCornerShape(4.dp)) {
                                Text(if (a.ready == true) "READY" else "SET UP", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = color, fontSize = 10.sp)
                            }
                        }
                        Text(a.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(a.status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
