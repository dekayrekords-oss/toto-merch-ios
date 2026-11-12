package com.meowmba.shared.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meowmba.shared.toto.TotoRobotBridge
import kotlinx.coroutines.delay

@Composable
fun TotoRobotScreen(onBack: () -> Unit) {
    val state by TotoRobotBridge.state.collectAsState()
    var endpoint by remember(state.endpoint) { mutableStateOf(state.endpoint) }
    var pin by remember(state.pin) { mutableStateOf(state.pin) }
    var token by remember(state.token) { mutableStateOf(state.token) }
    var editPairing by remember { mutableStateOf(false) }
    var heldAction by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            if (heldAction != null) {
                heldAction = null
                TotoRobotBridge.releaseMotion()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Toto Controls & Sensors", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back") }
            }

            Text(
                "Connect iPhone to Toto Orin for CoreMotion IMU, proximity, Vision face tracking, Roomba driving, Kinect tilt, and V14 Imperial Arm control.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                state.message,
                color = if (state.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TotoTetherControl(
                tethered = state.motionBlockers.any { it.contains("Tether", ignoreCase = true) },
                connected = state.connected,
                onTether = { TotoRobotBridge.setTetherLock(true) },
                onUntether = { TotoRobotBridge.setTetherLock(false) },
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editPairing = !editPairing }) {
                    Text(if (editPairing) "Hide pairing settings" else "Pairing settings")
                }
                TextButton(onClick = {
                    endpoint = TotoRobotBridge.DEFAULT_ENDPOINT
                    pin = TotoRobotBridge.DEFAULT_PIN
                    token = TotoRobotBridge.DEFAULT_TOKEN
                    TotoRobotBridge.clearPairingSettings()
                }) {
                    Text("Clear robot settings")
                }
            }

            if (editPairing) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS bridge endpoint") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Certificate SHA-256 pin") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Bearer token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }

            Button(
                onClick = { TotoRobotBridge.connect(endpoint, pin, token) },
                enabled = !state.connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.connecting) "Connecting…" else if (state.connected) "Reconnect to Toto" else "Connect to Toto")
            }

            if (state.connected || state.connecting) {
                OutlinedButton(
                    onClick = { TotoRobotBridge.stopAll(); TotoRobotBridge.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect · stop sharing")
                }
            }

            // iPhone Sensors, Camera & Active Face Tracking
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("iPhone Sensors, Camera & Face Tracking", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = state.mounted, onCheckedChange = { TotoRobotBridge.setMounted(it) })
                        Text("iPhone mounted in holder (moves V14 arm to Live Neutral)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.sensorSharing,
                            enabled = state.connected,
                            onCheckedChange = { TotoRobotBridge.setSensorSharing(it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Share CoreMotion IMU & Proximity")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.cameraSharing,
                            enabled = state.connected,
                            onCheckedChange = { TotoRobotBridge.setCameraSharing(it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Share Camera & Vision Face Tracking")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.trackingEnabled,
                            enabled = state.connected,
                            onCheckedChange = { TotoRobotBridge.setTrackingEnabled(it) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Active Face & Gaze Tracking (Eyes + V14 Wrist)")
                    }
                    Text(state.sensorSummary, style = MaterialTheme.typography.bodySmall)
                    Text(state.visionSummary, style = MaterialTheme.typography.bodySmall)
                    Text(state.speechSummary, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Live Robot Sensors Telemetry Readback
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Live Robot Sensors (Toto Orin)", style = MaterialTheme.typography.titleMedium)
                    Text(state.robotBatterySummary, style = MaterialTheme.typography.bodySmall)
                    Text(state.robotImuSummary, style = MaterialTheme.typography.bodySmall)
                    Text(state.robotTofSummary, style = MaterialTheme.typography.bodySmall)
                    Text(state.robotArmSummary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Status: ${state.lastIntentStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Roomba Base & Kinect Tilt Manual Controls
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Toto Movement (Roomba Base & Kinect Tilt)", style = MaterialTheme.typography.titleMedium)
                    Text(state.motionReason, style = MaterialTheme.typography.bodySmall)

                    listOf(
                        listOf("base_forward" to "Forward", "base_reverse" to "Reverse"),
                        listOf("base_left" to "Left", "base_right" to "Right"),
                    ).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (action, label) ->
                                IosHoldControl(
                                    label = label,
                                    action = action,
                                    enabled = state.connected && state.baseReady && (heldAction == null || heldAction == action),
                                    claim = { if (heldAction == null) { heldAction = action; true } else false },
                                    release = { if (heldAction == action) { heldAction = null; TotoRobotBridge.releaseMotion() } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("tilt_up" to "Tilt up", "tilt_down" to "Tilt down", "tilt_center" to "Center").forEach { (action, label) ->
                            OutlinedButton(
                                onClick = { TotoRobotBridge.stepTilt(action) },
                                enabled = state.connected && state.tiltReady && heldAction == null,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text(label) }
                        }
                    }


                    Button(
                        onClick = { TotoRobotBridge.stopAll() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("STOP Toto") }

                    state.stopMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    state.motionBlockers.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // V14 Imperial Arm, Wrist Jog, K-2SO Motifs & LeRobot Puppet Controls
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("V14 Imperial Arm & LeRobot Control", style = MaterialTheme.typography.titleMedium)
                    Text("Arm Poses (Neutral [0, 60, 30, -50, 0, 90] keeps shoulder relaxed in cradle)", style = MaterialTheme.typography.bodySmall)
                    listOf(
                        listOf("live_neutral" to "Live Neutral", "desk_stand" to "Desk Stand", "stand_tall" to "Stand Tall"),
                        listOf("look_left" to "Look Left", "look_right" to "Look Right", "park" to "Park Arm"),
                    ).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (preset, label) ->
                                OutlinedButton(
                                    onClick = { TotoRobotBridge.sendV14Preset(preset, force = true) },
                                    enabled = state.connected,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }

                    Text("Fine Wrist Pitch & Roll Jog", style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { TotoRobotBridge.jogV14Arm(deltaPitchDeg = 10.0) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Pitch +10°") }
                        OutlinedButton(onClick = { TotoRobotBridge.jogV14Arm(deltaPitchDeg = -10.0) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Pitch -10°") }
                        OutlinedButton(onClick = { TotoRobotBridge.jogV14Arm(reset = true) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Center") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { TotoRobotBridge.jogV14Arm(deltaRollDeg = -12.0) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Roll Left") }
                        OutlinedButton(onClick = { TotoRobotBridge.jogV14Arm(deltaRollDeg = 12.0) }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Roll Right") }
                    }

                    Text("K-2SO Expressive Motifs", style = MaterialTheme.typography.bodySmall)
                    listOf(
                        listOf("double_take" to "Double Take", "happy_wiggle" to "Happy Wiggle", "imperial_bow" to "Imperial Bow"),
                        listOf("floor_peek" to "Floor Peek", "sad_droop" to "Sad Droop", "laugh_chuckle" to "Laugh"),
                    ).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (motif, label) ->
                                OutlinedButton(
                                    onClick = { TotoRobotBridge.sendV14Motif(motif) },
                                    enabled = state.connected,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }

                    Text("LeRobot Kinesthetic Teaching (20 Hz Cubic-Spline)", style = MaterialTheme.typography.bodySmall)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { TotoRobotBridge.recordPuppetGesture() }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Record 4.5s Gesture") }
                        OutlinedButton(onClick = { TotoRobotBridge.playPuppetGesture() }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Play Gesture") }
                    }
                }
            }

            // 16-Pose Emotion Director Quick Triggers
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Toto LCD Eyes & Emotion Director", style = MaterialTheme.typography.titleMedium)
                    listOf(
                        listOf("happy", "curious", "playful", "affectionate"),
                        listOf("proud", "surprised", "sleepy", "idle"),
                    ).forEach { row ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { emo ->
                                OutlinedButton(
                                    onClick = { TotoRobotBridge.sendEmotion(emo) },
                                    enabled = state.connected,
                                    modifier = Modifier.weight(1f),
                                ) { Text(emo) }
                            }
                        }
                    }
                }
            }

            // Autonomous Navigation & Room Intents
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Autonomous Navigation & Room Intents", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("come_over") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Come Here") }
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("return_to_dock") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Go Dock") }
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("explore") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Explore") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("go_to_place", "living room") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Living Room") }
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("go_to_place", "dining room") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Dining Room") }
                        OutlinedButton(onClick = { TotoRobotBridge.sendNavigationIntent("go_to_place", "hallway") }, enabled = state.connected, modifier = Modifier.weight(1f)) { Text("Hallway") }
                    }
                }
            }
        }
    }
}

@Composable
private fun IosHoldControl(
    label: String,
    action: String,
    enabled: Boolean,
    claim: () -> Boolean,
    release: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    LaunchedEffect(pressed, enabled) {
        if (pressed && enabled && claim()) {
            try {
                if (TotoRobotBridge.startMotion(action)) {
                    while (true) {
                        delay(150)
                        if (!TotoRobotBridge.heartbeatMotion()) break
                    }
                }
            } finally {
                release()
            }
        }
    }
    OutlinedButton(
        onClick = {},
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier.heightIn(min = 52.dp),
    ) { Text(label) }
}
