package com.meowmba.shared.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.Face
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.meowmba.shared.resources.Res
import com.meowmba.shared.ui.theme.Card
import com.meowmba.shared.ui.theme.Surface
import com.meowmba.shared.ui.theme.OutlinedButton

internal val meowMenuItems = listOf("Settings", "Flow", "Desk", "Memory", "Files",
    "LLM", "Brain", "Robot", "Tools", "Agents", "Privacy", "Notes", "Auto", "Weather", "Background", "Avatar")

/** Android `BackgroundTiles`: slides and fades in over the dimmed current screen. */
@Composable
internal fun MeowMenuScene(visible: Boolean, onClose: () -> Unit, onSelect: (String) -> Unit,
                           avatarLabel: String = "Avatar", onAvatar: () -> Unit = {}) {
    androidx.compose.animation.AnimatedVisibility(visible = visible,
        enter = androidx.compose.animation.slideInVertically { -it / 8 } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it / 8 } + androidx.compose.animation.fadeOut()) {
        MeowMenuPanel(onClose, onSelect, avatarLabel, onAvatar)
    }
}

/** Android menu sizing, adaptive grid, order, colors and Material icon geometry. */
@Composable
private fun MeowMenuPanel(onClose: () -> Unit, onSelect: (String) -> Unit, avatarLabel: String, onAvatar: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .8f)).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null, onClick = onClose), contentAlignment = Alignment.Center) {
        Surface(Modifier.safeDrawingPadding().fillMaxWidth(.92f).fillMaxHeight(.78f),
            shape = MaterialTheme.shapes.extraLarge, color = colors.background) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Meow menu", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your companion, tools & settings", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                    // Quick way to your avatar (Lumen, Lumen Cat or the Orb), from any mode.
                    com.meowmba.shared.ui.theme.FilledTonalButton(onClick = onAvatar,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(androidx.compose.material.icons.Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(avatarLabel, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
                LazyVerticalGrid(GridCells.Adaptive(112.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(meowMenuItems) { name ->
                        val tint = when(name) {
                            "Settings", "Vending", "Robot", "Agents", "Weather" -> colors.tertiary
                            "Desk", "Files", "Brain", "Tools", "Auto", "Avatar" -> colors.secondary
                            else -> colors.primary
                        }
                        Card(onClick = { onSelect(name) }, modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(42.dp).background(colors.surfaceVariant, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                                    MenuIcon(name, tint)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun MenuIcon(name: String, tint: Color) {
    var paths by remember(name) { mutableStateOf<List<Path>>(emptyList()) }
    LaunchedEffect(name) {
        paths = runCatching { Res.readBytes("files/menu/$name.path").decodeToString().lines()
            .filter { it.isNotBlank() }.map { PathParser().parsePathString(it).toPath() } }.getOrDefault(emptyList())
    }
    Canvas(Modifier.size(26.dp)) { scale(size.width / 24f, size.height / 24f, androidx.compose.ui.geometry.Offset.Zero) {
        paths.forEach { drawPath(it, tint) }
    } }
}
