package com.meowmba.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun TotoPersonalityPanel(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("About Toto") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = dialogScrollMaxHeight(0.6f)).verticalScroll(rememberScrollState())) {
            Text("Warm, curious, dryly funny and repair-minded. His personality favours comfortable silence, natural follow-ups and speaking for a real reason. Robot listening and greeting settings control when he actually speaks.\n\nMemory is bounded and consent-based. Mood and expressions describe software state; they are not proof of feelings, perception or consciousness.\n\nRobot software & iOS Bridge\n• Optional pinned-HTTPS bridge configured by the owner\n• CoreMotion IMU, proximity, camera & Vision face tracking\n• Hold-to-move Roomba base & Kinect tilt controls\n\nOpen 'Toto Controls' from the top-left button or settings menu to configure a connection.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
