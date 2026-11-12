package com.meowmba.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Kiosk-sized wheel tether control. Calls the existing Toto `setTetherLock` /
 * `tether_mode` intent. Does not invent a new robot protocol.
 */
@Composable
fun TotoTetherControl(
    tethered: Boolean,
    connected: Boolean,
    onTether: () -> Unit,
    onUntether: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "WHEEL TETHER",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.5.sp,
            )
            Text(
                if (tethered) "Tethered" else "Untethered",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    !connected -> "Connect to Toto to change the wheel tether."
                    tethered -> "Wheels are locked. Toto stays put. Kinect tilt still works."
                    else -> "Wheels can move. Tether locks them so Toto stays in place."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TetherActionButton(
                    label = "Tether",
                    emphasized = !tethered,
                    enabled = connected,
                    onClick = onTether,
                )
                TetherActionButton(
                    label = "Untether",
                    emphasized = tethered,
                    enabled = connected,
                    onClick = onUntether,
                )
            }
        }
    }
}

@Composable
private fun RowScope.TetherActionButton(
    label: String,
    emphasized: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val modifier = Modifier.weight(1f).heightIn(min = 72.dp)
    if (emphasized) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
