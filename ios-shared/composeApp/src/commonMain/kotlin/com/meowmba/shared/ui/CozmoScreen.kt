package com.meowmba.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.robot.Cozmo
import com.meowmba.shared.robot.CozmoProtocol
import com.meowmba.shared.robot.CozmoReactions
import com.meowmba.shared.robot.CozmoSession
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Android CozmoScreen (builds 175–179): direct Wi-Fi controls, camera and voice for Cozmo. */
@Composable
internal fun CozmoScreen(onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val state by Cozmo.state.collectAsState()
    val frame by Cozmo.image.collectAsState()
    val camera = remember(frame) { frame?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
    var result by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { Cozmo.stop(); Cozmo.setCamera(false) } }
    val action: (String) -> Unit = { result = Cozmo.action(it) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cozmo", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onBack) { Text("Close") }
            }
            Text("Cozmo · Wi-Fi companion", style = MaterialTheme.typography.titleMedium)
            Text("Wake Cozmo on his charger. In Settings › Wi-Fi, join his network using the password on his face, close the official Cozmo app, then connect here. Allow Local Network access when iOS asks.",
                style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                Button(enabled = !state.connecting && !state.connected, onClick = { Cozmo.connect() }) { Text(if (state.connecting) "Connecting…" else "Connect") }
            }
            Text(state.message, style = MaterialTheme.typography.titleSmall)
            if (state.firmware.isNotEmpty()) Text("Firmware ${state.firmware} · ${state.serial}", style = MaterialTheme.typography.bodySmall)
            Text("Experimental integration: bench testing on your Cozmo is still required. Cozmo's Wi-Fi has no internet; iPhone keeps chat on cellular when it can.",
                style = MaterialTheme.typography.bodySmall)
            if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Cozmo.stop(); result = "Stop requested; check Cozmo has stopped" }, enabled = state.connected || state.connecting,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("STOP") }
                OutlinedButton(onClick = Cozmo::disconnect, enabled = state.connected || state.connecting) { Text("Disconnect") }
            }
            state.telemetry?.let { t ->
                Text("Battery ${CozmoSession.fmt(t.batteryVolts, 2)} V · Head ${t.headDegrees.toInt()}° · Lift ${t.liftMm.toInt()} mm")
                val notes = listOfNotNull("Charging".takeIf { t.charging }, "On charger".takeIf { t.onCharger },
                    "Picked up".takeIf { t.pickedUp }, "Edge detected".takeIf { t.cliff }, "Falling".takeIf { t.falling })
                if (notes.isNotEmpty()) Text(notes.joinToString(" · "))
                Text("Position ${t.x.toInt()}, ${t.y.toInt()} mm · Wheels ${t.leftSpeed.toInt()} / ${t.rightSpeed.toInt()} mm/s", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Head and lift", style = MaterialTheme.typography.titleMedium)
            CozmoButtons(listOf("look_up" to "Look up", "look_center" to "Center", "look_down" to "Look down"), state.connected, action)
            CozmoButtons(listOf("lift_up" to "Lift up", "lift_middle" to "Half lift", "lift_down" to "Lift down"), state.connected, action)
            CozmoButtons(listOf("calibrate" to "Calibrate head and lift"), state.connected, action)
            Text("If head or lift does not respond, place Cozmo on a clear floor and calibrate. This moves both joints; keep fingers clear.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Short floor moves", modifier = Modifier.weight(1f))
                Switch(state.wheelsEnabled, Cozmo::setWheels, enabled = state.connected)
            }
            Text("Use on a clear floor. Each tap moves for at most 0.35 seconds. Stop and disconnect disable driving. Do not rely on edge sensors on a table.", style = MaterialTheme.typography.bodySmall)
            CozmoButtons(listOf("forward" to "Forward", "back" to "Back", "left" to "Left", "right" to "Right"), state.connected && state.wheelsEnabled, action)
            CozmoButtons(listOf("arc_left" to "Curve left", "arc_right" to "Curve right"), state.connected && state.wheelsEnabled, action)
            HorizontalDivider()
            Text("Personality", style = MaterialTheme.typography.titleMedium)
            Text("Original head/lift reactions — use on a clear floor. STOP cancels the sequence. These are not the official app's games or animations.", style = MaterialTheme.typography.bodySmall)
            CozmoReactions.names.chunked(3).forEach { names -> CozmoButtons(names.map { it to it.replaceFirstChar(Char::uppercaseChar) }, state.connected, action) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("React to Meow, pickup and cubes; blink", modifier = Modifier.weight(1f))
                Switch(state.companionEyes, Cozmo::setCompanion, enabled = state.connected)
            }
            CozmoProtocol.EXPRESSIONS.chunked(3).forEach { names -> CozmoButtons(names.map { it to it.replaceFirstChar(Char::uppercaseChar) }, state.connected, action) }
            Text("Backpack lights", style = MaterialTheme.typography.titleMedium)
            CozmoProtocol.COLORS.keys.toList().chunked(3).forEach { names -> CozmoButtons(names.map { "light_$it" to it.replaceFirstChar(Char::uppercaseChar) }, state.connected, action) }
            CozmoButtons(listOf("headlight_on" to "Camera light on", "headlight_off" to "Light off"), state.connected, action)
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cozmo camera", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(state.cameraEnabled, Cozmo::setCamera, enabled = state.connected)
            }
            Text("Local grayscale preview, about 3 frames/sec. Turns off when you leave this screen. Images are not stored or sent to an AI service.", style = MaterialTheme.typography.bodySmall)
            if (state.cameraEnabled) {
                camera?.let { Image(it, "Cozmo live camera", Modifier.fillMaxWidth().aspectRatio(4f / 3f)) } ?: Text("Waiting for a camera frame…")
            }
            Text("Cubes", style = MaterialTheme.typography.titleMedium)
            if (state.cubes.isEmpty()) Text("Tap a nearby powered cube to wake it. Discovered cubes appear here.")
            state.cubes.forEach { cube ->
                OutlinedButton(enabled = state.connected && cube.objectId == null, onClick = { result = Cozmo.connectCube(cube.factoryId) }) {
                    Text("Cube ${cube.type} · ${if (cube.objectId != null) "Connected" else "Connect"} · ${cube.rssi} dBm")
                }
            }
            if (state.lastEvent.isNotBlank()) Text(state.lastEvent)
            Text("Voice examples: ‘Cozmo look up’, ‘Cozmo lift up’, ‘Cozmo happy’, ‘Cozmo lights blue’, ‘Cozmo status’, ‘Cozmo stop’. Meow hears and speaks through your phone.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CozmoButtons(actions: List<Pair<String, String>>, enabled: Boolean, onAction: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.forEach { (action, label) ->
            OutlinedButton(onClick = { onAction(action) }, enabled = enabled, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text(label) }
        }
    }
}
