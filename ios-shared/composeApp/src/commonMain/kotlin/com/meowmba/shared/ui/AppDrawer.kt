package com.meowmba.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class DrawerApp(val label: String, val icon: ImageVector, val url: String)

private fun iconFor(name: String): ImageVector = when (name) {
    "Phone" -> Icons.Default.Call; "Messages", "WhatsApp", "Messenger", "Signal", "Telegram" -> Icons.AutoMirrored.Filled.Message
    "Mail", "Gmail", "Outlook" -> Icons.Default.Email; "Safari", "Chrome" -> Icons.Default.Public
    "Maps", "Google Maps", "Waze" -> Icons.Default.Map; "Calendar" -> Icons.Default.CalendarMonth
    "Photos", "Google Photos", "Instagram", "Pinterest" -> Icons.Default.Photo
    "Music", "Spotify", "Podcasts" -> Icons.Default.MusicNote; "Notes", "Notion" -> Icons.Default.StickyNote2
    "Reminders" -> Icons.Default.Checklist; "FaceTime", "Zoom", "Teams" -> Icons.Default.Videocam
    "App Store" -> Icons.Default.Apps; "Books", "Duolingo" -> Icons.Default.MenuBook; "Files", "Google Drive" -> Icons.Default.Folder
    "Shortcuts" -> Icons.Default.Bolt; "Settings" -> Icons.Default.Settings
    "YouTube", "Netflix", "Twitch", "TikTok" -> Icons.Default.PlayCircle
    "Uber", "Lyft" -> Icons.Default.DirectionsCar; "Venmo", "PayPal" -> Icons.Default.Payments
    "Amazon" -> Icons.Default.ShoppingCart; "Slack", "Discord", "Reddit", "X", "Facebook", "LinkedIn", "Snapchat" -> Icons.Default.Forum
    "ChatGPT" -> Icons.Default.Psychology
    else -> Icons.Default.Apps
}

/** Android's swipe-up app drawer, as iOS allows: system apps by link, then Meow's own tools. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppDrawerSheet(onDismiss: () -> Unit, onTool: (String) -> Unit) {
    val apps = androidx.compose.runtime.remember { com.meowmba.shared.platform.IosApps.available() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Apps", style = MaterialTheme.typography.titleLarge)
            Text("Apps on this iPhone that Meow can open, then Meow's own tools.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val entries: List<Pair<DrawerApp, () -> Unit>> =
                apps.map { app -> DrawerApp(app.name, iconFor(app.name), app.url) to {
                    com.meowmba.shared.platform.PlatformDrivers.apps?.open(app.url) {}; onDismiss() } } +
                listOf("Memory" to Icons.Default.Storage, "LLM" to Icons.Default.Psychology, "Notes" to Icons.Default.Description,
                    "Weather" to Icons.Default.WbSunny, "Background" to Icons.Default.Wallpaper, "Avatar" to Icons.Default.Face)
                    .map { (name, icon) -> DrawerApp(name, icon, "") to { onTool(name) } }
            LazyVerticalGrid(GridCells.Adaptive(76.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(420.dp)) {
                items(entries) { (app, action) ->
                    Column(Modifier.clickable(onClick = action), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                            contentAlignment = Alignment.Center) {
                            Icon(app.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(app.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
    }
}
