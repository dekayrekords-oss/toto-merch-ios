/* Hallmark · pre-emit critique: P4 H4 E3 S4 R5 V4
 * genre: modern-minimal · macrostructure: native workbench · design-system: design.md */
package com.meowmba.shared.ui

import com.meowmba.shared.ui.theme.FilterChip

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal object AvatarSetupTokens {
    val compact = 8.dp
    val item = 12.dp
    val section = 24.dp
    val page = 20.dp
    val corner = 20.dp
}

@Composable
internal fun SetupSection(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = AvatarSetupTokens.section, bottom = AvatarSetupTokens.compact), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetupModeChooser(habitatMode: Boolean, onHabitatMode: (Boolean) -> Unit) {
    SetupSection("Your companion’s space")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AvatarSetupTokens.compact)) {
        FilterChip(selected = !habitatMode, onClick = { onHabitatMode(false) }, label = { Text("Backdrop") })
        FilterChip(selected = habitatMode, onClick = { onHabitatMode(true) }, label = { Text("Play habitat") })
    }
    Text(if (habitatMode) "A place to explore, play and care for your companion."
        else "Your avatar over a plain, picture or animated background.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SetupSizeControl(scale: Float, onScale: (Float) -> Unit) {
    val value = if (scale.isFinite()) scale.coerceIn(.5f, 3f) else 2f
    SetupSection("Avatar size", "Applies in every background and habitat.")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onScale(2f) }, enabled = value != 2f) { Text("Reset") }
    }
    Slider(value = value, onValueChange = { onScale(it.coerceIn(.5f, 3f)) }, valueRange = .5f..3f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Avatar size" })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("50%", style = MaterialTheme.typography.bodySmall)
        Text("300%", style = MaterialTheme.typography.bodySmall)
    }
}
