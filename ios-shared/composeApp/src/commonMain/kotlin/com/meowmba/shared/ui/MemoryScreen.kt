package com.meowmba.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.memory.MemoryItem
import com.meowmba.shared.memory.MemoryType
import com.meowmba.shared.platform.PlatformDrivers
import com.meowmba.shared.ui.theme.AlertDialog
import com.meowmba.shared.ui.theme.OutlinedButton
import com.meowmba.shared.ui.theme.Surface

private enum class MemoryTab(val label: String) { ALL("All"), IMAGES("Images"), VIDEOS("Videos"), PROJECTS("Projects") }

/**
 * Ported from Android `ui/companion/MemoryScreen.kt` (build 171): search, inspect, delete, clear-all,
 * export, retention and the pause switch. Android's MetaMemory/mem0 lines become the iOS facts.
 */
@Composable
internal fun MemoryScene(viewModel: CompanionViewModel, onBack: () -> Unit) {
    val snapshot by viewModel.memory.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MemoryTab.ALL) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val searching = query.isNotBlank()
    val pool = if (searching) snapshot.items.filter { m ->
        m.content.contains(query, true) || m.tags.any { it.contains(query, true) } } else snapshot.items
    val displayed = (if (searching) pool else when (selectedTab) {
        MemoryTab.IMAGES -> pool.filter { "image" in it.tags || "canvas" in it.tags }
        MemoryTab.VIDEOS -> pool.filter { "video" in it.tags }
        MemoryTab.PROJECTS -> pool.filter { it.type == MemoryType.PROJECT || "project" in it.tags }
        MemoryTab.ALL -> pool
    }).sortedByDescending { it.timestamp }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Memory", style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text("${snapshot.items.size} memories stored on this device",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("Stored only on this iPhone · nothing is uploaded",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Text("Semantic memory sync off", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

            Spacer(Modifier.height(16.dp))
            val tabs = MemoryTab.entries
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(selectedTab), containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary, edgePadding = 0.dp) {
                tabs.forEach { tab ->
                    Tab(selected = selectedTab == tab, onClick = { selectedTab = tab; query = "" }, text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (tab) {
                                MemoryTab.ALL -> null
                                MemoryTab.IMAGES -> Icons.Default.Image
                                MemoryTab.VIDEOS -> Icons.Default.Videocam
                                MemoryTab.PROJECTS -> Icons.Default.Folder
                            }
                            if (icon != null) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(tab.label, fontSize = 12.sp)
                        }
                    })
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Search memories…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface),
                shape = RoundedCornerShape(12.dp))

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Save new memories", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                    Text(if (snapshot.paused) "Paused — nothing is written" else "On — companion remembers",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Switch(checked = !snapshot.paused, onCheckedChange = { viewModel.memory.setPaused(!it) })
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep for:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                listOf(30 to "30d", 90 to "90d", 365 to "1y", 0 to "∞").forEach { (days, label) ->
                    val selected = snapshot.retentionDays == days
                    TextButton(onClick = { viewModel.memory.setRetention(days) },
                        colors = ButtonDefaults.textButtonColors(contentColor =
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val json = viewModel.memory.export()
                    val share = PlatformDrivers.share
                    if (share != null) share.shareText(json, "Meowmba memory export")
                    else { clipboard.setText(AnnotatedString(json)); exportNotice = "Memory export copied" }
                }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    Text("Export JSON", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Clear all", fontSize = 12.sp)
                }
            }
            exportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp) }

            Spacer(Modifier.height(16.dp))
            if (displayed.isEmpty()) {
                Text(if (searching) "No memories match \"$query\"."
                    else "No memories yet. Say \"remember that …\" or just talk — checkpoints appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(displayed, key = { it.id }) { memory ->
                        MemoryCard(memory, onDelete = { viewModel.memory.delete(memory.id) })
                    }
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all memories?") },
        text = { Text("This permanently deletes every stored memory and the conversation history. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.memory.clear(); viewModel.clearConversation() }) {
            Text("Delete everything", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable
private fun MemoryCard(memory: MemoryItem, onDelete: () -> Unit) {
    val typeColor = when (memory.type) {
        MemoryType.SEMANTIC, MemoryType.EPISODIC -> MaterialTheme.colorScheme.primary
        MemoryType.PROJECT -> MaterialTheme.colorScheme.tertiary
        MemoryType.PREFERENCE -> MaterialTheme.colorScheme.secondary
    }
    val offset = PlatformTime.utcOffsetMinutes()
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = typeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                        Text(memory.type.name, color = typeColor, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    // Android "MMM d, HH:mm".
                    val stamp = DateText.localStamp(memory.timestamp, offset)
                    Text("${DateText.shortDate(memory.timestamp, offset).substringBeforeLast(',')}, ${stamp.substring(11, 16)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(memory.content, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
                if (memory.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(memory.tags.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete memory",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}
