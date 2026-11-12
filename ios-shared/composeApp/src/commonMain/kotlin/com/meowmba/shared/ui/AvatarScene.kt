package com.meowmba.shared.ui

import androidx.compose.material.icons.filled.Close

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.meowmba.shared.ui.theme.FilterChip
import com.meowmba.shared.ui.theme.Surface
import com.meowmba.shared.ui.theme.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meowmba.shared.companion.AvatarPose
import com.meowmba.shared.ui.lumen.AvatarPerformanceCatalog
import com.meowmba.shared.ui.lumen.AvatarAnimal
import com.meowmba.shared.ui.lumen.AvatarColors

/** Android avatar setup layout, backed by the shared Lumen forms and saved appearance. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvatarScene(
    pose: AvatarPose,
    mode: AvatarMode,
    onMode: (AvatarMode) -> Unit,
    onClose: () -> Unit,
    onFlow: () -> Unit,
    onDesk: () -> Unit,
    onChat: () -> Unit,
) {
    val appearance by AvatarAppearanceStore.state.collectAsState()
    var tab by remember { mutableStateOf("Look") }
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your avatar", style = MaterialTheme.typography.headlineSmall)
                    Text("Appearance, behavior & play", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Close avatar setup") }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1.7f).clip(RoundedCornerShape(24.dp))
                .background(colors.surfaceVariant)) {
                LumenAvatar(pose, Modifier.fillMaxSize(), mode = mode, magnification = appearance.scale)
            }
            Text(mode.title + if (mode == AvatarMode.LUMEN) " · " + appearance.animal.label else "",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text("Selected · changes apply immediately", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Look", "Space", "Behavior", "Animations").forEach { option ->
                    FilterChip(selected = tab == option, onClick = { tab = option }, label = { Text(option) })
                }
            }
            when (tab) {
                "Look" -> {
                    AvatarSection("Choose an avatar")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AvatarMode.entries.filter { it != AvatarMode.OFF }.forEach { option ->
                            FilterChip(selected = mode == option, onClick = { onMode(option) },
                                label = { Text(option.title) })
                        }
                    }
                    Text(mode.description, style = MaterialTheme.typography.bodySmall)
                    if (mode == AvatarMode.LUMEN) {
                        AvatarSection("Choose your character")
                        Text("Pick a character for Lumen, including Clippy and the classic wizard. Your choice stays saved.",
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvatarAnimal.entries.forEach { animal ->
                                FilterChip(selected = appearance.animal == animal,
                                    onClick = { AvatarAppearanceStore.update(appearance.copy(animal = animal)) },
                                    label = { Text(animal.label) })
                            }
                        }
                    }
                    AvatarSection("Colors")
                    AvatarColorRow("Eye color", appearance.eye) {
                        AvatarAppearanceStore.update(appearance.copy(eye = it))
                    }
                    AvatarColorRow("Feature color", appearance.feature) {
                        AvatarAppearanceStore.update(appearance.copy(feature = it))
                    }
                    AvatarColorRow("Glow color", appearance.glow) {
                        AvatarAppearanceStore.update(appearance.copy(glow = it))
                    }
                }
                "Space" -> {
                    AvatarSection("Size & play space")
                    Text("Keep a large avatar on your chosen background.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("Avatar size", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                    Slider(value = appearance.scale,
                        onValueChange = { AvatarAppearanceStore.update(appearance.copy(scale = it)) },
                        valueRange = .7f..2.5f)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Small", style = MaterialTheme.typography.bodySmall)
                        Text("Large", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Toto stays on the avatar surface. Chat, provider/model, voice, memory and tools remain available from the compact controls.",
                        Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                "Behavior" -> {
                    AvatarSection("Movement & personality")
                    Text("The companion follows its current expression and blinks at rest.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Camera following, care, and the full Android behavior controls are not connected on iOS yet.",
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                "Animations" -> {
                    AvatarSection("Play an animation")
                    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                        label = { Text("Find an animation") }, singleLine = true)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf("All") + AvatarPerformanceCatalog.entries.map { it.category }.distinct()).forEach { option ->
                            FilterChip(selected = category == option, onClick = { category = option },
                                label = { Text(option) })
                        }
                    }
                    AvatarPerformanceCatalog.entries.filter {
                        (category == "All" || it.category == category) &&
                            (search.isBlank() || it.title.contains(search, true) || it.aliases.any { alias -> alias.contains(search, true) })
                    }.forEach { performance ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(performance.title, style = MaterialTheme.typography.titleMedium)
                                Text(performance.category, style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                            }
                            TextButton(onClick = { AvatarAppearanceStore.play(performance); onClose() }) { Text("Play") }
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun AvatarSection(title: String) {
    Text(title, Modifier.padding(top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvatarColorRow(label: String, selected: Long, onSelect: (Long) -> Unit) {
    Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AvatarColors.swatches.forEach { swatch ->
            Box(Modifier.size(32.dp)
                .border(if (swatch.argb == selected) 2.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                .padding(4.dp).clip(CircleShape).background(Color(swatch.argb))
                .clickable { onSelect(swatch.argb) })
        }
    }
}
