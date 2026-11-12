package com.meowmba.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.toto.LooiRobotBridge

@Composable
fun LooiControlScreen(onClose: () -> Unit) {
    val state by LooiRobotBridge.state.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (state.devices.isEmpty() && !state.connected && !state.connecting && !state.scanning) {
            LooiRobotBridge.scan()
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LOOI", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Text(
                "This robot talks over Bluetooth LE, not Wi-Fi. Scan for a name containing LOOI, then Connect. Close the official LOOI app first — it may already be holding the connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(state.status, fontWeight = FontWeight.Medium)
                    val phase = when {
                        state.ready -> "Ready"
                        state.connected -> "Connected"
                        state.connecting -> "Connecting"
                        state.scanning -> "Scanning"
                        else -> "Not connected"
                    }
                    val phaseColor = when {
                        state.ready -> MaterialTheme.colorScheme.primary
                        state.connected -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        when {
                            state.ready -> "Ready · Bluetooth LE · characteristics found"
                            state.connected -> "Connected · Bluetooth LE · link up"
                            state.connecting -> "Connecting…"
                            state.scanning -> "Scanning…"
                            else -> "Not connected"
                        },
                        color = phaseColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Phase: $phase", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { LooiRobotBridge.scan() },
                    enabled = !state.scanning && !state.connecting,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (state.scanning) "Scanning…" else "Scan") }
                Button(
                    onClick = { LooiRobotBridge.connectBle() },
                    enabled = !state.connecting,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                ) { Text(if (state.connected) "Reconnect" else "Connect") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { LooiRobotBridge.disconnect() },
                enabled = state.connected || state.connecting || state.scanning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Disconnect") }
            Spacer(Modifier.height(20.dp))
            Text("Motion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Motors start OFF every launch. Merch greet / select / pay cues and face-follow only move LOOI while Motors is ON.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { LooiRobotBridge.setMotorsOn(!state.motorsOn) },
                enabled = state.ready || state.motorsOn || (state.transport == "tcp" && state.connected),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.motorsOn) Color(0xFF2E7D32) else Color(0xFF455A64),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) {
                Text(
                    if (state.motorsOn) "Motors ON  ·  tap to turn off" else "Motors OFF  ·  tap to arm writes",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { LooiRobotBridge.stopMotors() },
                enabled = state.connected || state.motorsOn,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            ) {
                Text("STOP", fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                LooiRobotBridge.MOTOR_WRITE_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text("Posture & Merch Cues", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Conservative bounded poses & <= 30% pulses (armed only while Motors is ON).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { LooiRobotBridge.setNeckPitchManual(-0.55f, "Neck: Look Up") },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Look Up") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.setNeckPitchManual(0f, "Neck: Level") },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Level") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.setNeckPitchManual(0.45f, "Neck: Look Down") },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Look Down") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { LooiRobotBridge.turnPulse(-22, 2) },
                    enabled = state.motorsOn && state.transport == "ble",
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Turn Left") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.nod() },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Nod") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.turnPulse(22, 2) },
                    enabled = state.motorsOn && state.transport == "ble",
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Turn Right") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { LooiRobotBridge.cue("greet") },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) { Text("Greet Cue") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.cue("select") },
                    enabled = state.motorsOn,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) { Text("Select Cue") }
                OutlinedButton(
                    onClick = { LooiRobotBridge.setHeadlight(!state.headlightOn) },
                    enabled = state.motorsOn && state.transport == "ble",
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) { Text(if (state.headlightOn) "Light OFF" else "Light ON") }
            }
            if (state.devices.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Found nearby", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Tap a device to connect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.devices.forEach { device ->
                    Row(
                        Modifier.fillMaxWidth().clickable { LooiRobotBridge.connectBle(device.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name.ifBlank { "LOOI" }, style = MaterialTheme.typography.bodyLarge)
                            Text(device.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAdvanced) "Hide advanced Wi-Fi/TCP" else "Advanced: Wi-Fi/TCP (not used by this LOOI)")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = LooiRobotBridge::setHost,
                    label = { Text("Base host (IP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.port,
                    onValueChange = LooiRobotBridge::setPort,
                    label = { Text("Base port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { LooiRobotBridge.connectTcp() },
                    enabled = !state.connecting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Connect over Wi-Fi/TCP") }
            }
        }
    }
}
