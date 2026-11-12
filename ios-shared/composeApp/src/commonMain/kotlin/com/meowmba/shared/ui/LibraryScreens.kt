package com.meowmba.shared.ui

import androidx.compose.material.icons.automirrored.filled.OpenInNew

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meowmba.shared.bridge.DesktopBridge
import androidx.compose.ui.unit.sp
import com.meowmba.shared.agent.OnDeviceModel
import com.meowmba.shared.core.DeviceInfo
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.ProviderPresets
import com.meowmba.shared.ui.theme.Button
import com.meowmba.shared.ui.theme.Card
import com.meowmba.shared.ui.theme.FilterChip
import com.meowmba.shared.ui.theme.OutlinedButton
import com.meowmba.shared.ui.theme.OutlinedCard
import com.meowmba.shared.ui.theme.Surface

// ── Settings (Android ui/companion/SettingsScreen.kt) ────────────────────────────────────────────

private data class SettingsCategory(val title: String, val summary: String, val icon: ImageVector)

/** Android's twelve categories, same order, titles and summaries; icons are the Material ones. */
private val SETTINGS_CATEGORIES = listOf(
    SettingsCategory("Merch table", "Customer menu, inventory editor and PayPal test payments", Icons.Default.ShoppingCart),
    SettingsCategory("Voice & behavior", "Wake phrase, conversation and listening", Icons.Default.Mic),
    SettingsCategory("AI & models", "LLM settings, agent routing and downloads", Icons.Default.Psychology),
    SettingsCategory("Images", "AI Horde, Cloudflare and on-phone images", Icons.Default.Image),
    SettingsCategory("Music & video", "Media-generation services", Icons.Default.Movie),
    SettingsCategory("Phone & device", "App control, access and device behavior", Icons.Default.PhoneIphone),
    SettingsCategory("Appearance", "Dark/light mode, themes and avatars", Icons.Default.Palette),
    SettingsCategory("Neural / Lumen", "Avatar reactions, neural brain and sensitivity", Icons.Default.Bolt),
    SettingsCategory("Connections & memory", "Desktop bridge, servers and shared memory", Icons.Default.Link),
    SettingsCategory("Auto", "Driving, navigation and vehicle settings", Icons.Default.DirectionsCar),
    SettingsCategory("Backup & updates", "Protect keys, restore data and update safely", Icons.Default.Security),
    SettingsCategory("Help & feedback", "Beta help, testing and support", Icons.AutoMirrored.Filled.HelpOutline),
)

/** Android Settings chrome: back arrow + headlineSmall title, then a 20/12dp scrolling body. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (title == "Settings") "Close settings" else "Back")
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                content = content)
        }
    }
}

@Composable
internal fun SettingsHomeScene(onOpen: (String) -> Unit, onBack: () -> Unit, onSetup: () -> Unit) {
    SettingsPage("Settings", onBack) {
        Text("Everything in its place. Tap a category to open its options.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Finish setup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("AI, voice, images and connections.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onSetup) { Text("Start setup") }
            }
        }
        Spacer(Modifier.height(16.dp))
        SETTINGS_CATEGORIES.forEach { category ->
            SettingsCategoryCard(category) { onOpen(category.title) }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SettingsCategoryCard(category: SettingsCategory, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(category.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Android Settings → Appearance: color mode, app theme, then the avatar list. */
@Composable
internal fun AppearanceScene(onBack: () -> Unit, onHome: () -> Unit, onAvatar: () -> Unit,
                             avatarMode: AvatarMode, onAvatarMode: (AvatarMode) -> Unit,
                             idleAvatar: String = "lumen", idleMinutes: Int = 2, onIdleAvatar: (String, Int) -> Unit = { _, _ -> }) {
    val mode by appearanceStore.mode.collectAsState()
    val theme by appearanceStore.theme.collectAsState()
    SettingsPage("Appearance", onBack) {
        Spacer(Modifier.height(24.dp))
        Text("Color mode", style = MaterialTheme.typography.titleMedium)
        AppearanceMode.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { appearanceStore.setMode(option) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { appearanceStore.setMode(option) })
                Text(option.name.lowercase().replaceFirstChar { it.titlecase() })
            }
        }
        Text("App theme", style = MaterialTheme.typography.titleMedium)
        AppTheme.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { appearanceStore.setTheme(option) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = theme == option, onClick = { appearanceStore.setTheme(option) })
                Text(option.name.lowercase().replaceFirstChar { it.titlecase() })
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Avatar", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        AvatarMode.entries.filter { it != AvatarMode.OFF }.forEach { option ->
            Row(Modifier.fillMaxWidth().clickable { onAvatarMode(option) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = option == avatarMode, onClick = { onAvatarMode(option) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(option.title, color = MaterialTheme.colorScheme.onSurface)
                    Text(option.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Desk sideways zoom", style = MaterialTheme.typography.titleMedium)
        val deskZoom by com.meowmba.shared.ui.desk.DeskView.landscapeZoom.collectAsState()
        Text("How close the Desk looks with the phone on its side. 1× fits the room from the top of the window to the floor; " +
            "above that the view follows the cat. Upright is unchanged.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Slider(deskZoom, com.meowmba.shared.ui.desk.DeskView::setLandscapeZoom, Modifier.weight(1f),
                valueRange = com.meowmba.shared.ui.desk.DeskView.MIN_ZOOM..com.meowmba.shared.ui.desk.DeskView.MAX_ZOOM)
            Text("${(deskZoom * 10).toInt() / 10.0}×", Modifier.padding(start = 8.dp))
        }
        TextButton({ com.meowmba.shared.ui.desk.DeskView.setLandscapeZoom(1f) }) { Text("Reset to fit") }
        Text("When idle", style = MaterialTheme.typography.titleMedium)
        Text("On Flow and Desk, after a while without a touch Meow rests on screen as an avatar. New things cross the screen; tap to come back.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.meowmba.shared.ui.flow.IdleAvatarOptions.MODES.forEach { (key, label) ->
                FilterChip(selected = idleAvatar == key, onClick = { onIdleAvatar(key, idleMinutes) }, label = { Text(label) })
            }
        }
        if (idleAvatar != "off") {
            Text("After", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.meowmba.shared.ui.flow.IdleAvatarOptions.MINUTES.forEach { m ->
                    FilterChip(selected = idleMinutes == m, onClick = { onIdleAvatar(idleAvatar, m) }, label = { Text("$m min") })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onAvatar, modifier = Modifier.fillMaxWidth()) { Text("Open avatar studio") }
        Spacer(Modifier.height(24.dp))
    }
}

/** Android Settings → Voice & behavior, reduced to what iOS runs: mic, spoken replies, recognizer. */
@Composable
internal fun VoiceSettingsScene(viewModel: CompanionViewModel, onBack: () -> Unit) {
    val micEnabled by viewModel.micEnabled.collectAsState()
    val voice by viewModel.companionVoice.collectAsState()
    SettingsPage("Voice & behavior", onBack) {
        Spacer(Modifier.height(12.dp))
        PrivacyItem("Microphone", "Tap the big mic to talk; tap again when you're done. Never enabled silently.",
            micEnabled, Icons.Default.Mic, viewModel::setMicEnabled)
        PrivacyItem("Companion Voice", "Read replies aloud after you speak. Typed chats stay quiet.",
            voice, Icons.AutoMirrored.Filled.VolumeUp, viewModel::setCompanionVoice)
        Spacer(Modifier.height(16.dp))
        Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
        Text("Apple Speech. Recognition stays on this iPhone when your language supports on-device recognition.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Voice call", style = MaterialTheme.typography.titleMedium)
        Text("The phone button in chat keeps listening between replies until you end the call.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Toto's voice", style = MaterialTheme.typography.titleMedium)
        var totoSource by remember { mutableStateOf(com.meowmba.shared.voice.TotoVoicePreferences.source) }
        PrivacyItem(
            "Toto voice: On phone",
            "Default. An original Toto voice, generated on this iPhone with Kokoro-82M when the on-device pack is installed. The iPhone's built-in voice is used only as an emergency fallback.",
            totoSource == com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE,
            Icons.AutoMirrored.Filled.VolumeUp,
        ) { on ->
            totoSource = if (on) com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE
                else com.meowmba.shared.voice.TotoVoiceSource.SYSTEM
            com.meowmba.shared.voice.TotoVoicePreferences.source = totoSource
        }
        PrivacyItem(
            "Use Lion voice (advanced)",
            "Optional and off by default. Sends spoken text to Meow Lion. Not used for merch unless you turn this on.",
            totoSource == com.meowmba.shared.voice.TotoVoiceSource.LION,
            Icons.Default.Cloud,
        ) { on ->
            totoSource = if (on) com.meowmba.shared.voice.TotoVoiceSource.LION
                else com.meowmba.shared.voice.TotoVoiceSource.ON_PHONE
            com.meowmba.shared.voice.TotoVoicePreferences.source = totoSource
        }
    }
}

@Composable
internal fun MerchStaffScene(viewModel: CompanionViewModel, onBack: () -> Unit) {
    val snap by viewModel.merch.collectAsState()
    val paypal by viewModel.paypalSettings.collectAsState()
    SettingsPage("Merch table", onBack) {
        com.meowmba.shared.merch.MerchStaffScreen(
            snapshot = snap,
            controller = viewModel.merchTable,
            paypal = paypal,
            onPaypalChange = viewModel::setPaypalSettings,
        )
    }
}

// ── Privacy (Android ui/companion/PrivacyDashboard.kt) ───────────────────────────────────────────

@Composable
internal fun PrivacyScene(viewModel: CompanionViewModel, onBack: () -> Unit) {
    val micEnabled by viewModel.micEnabled.collectAsState()
    val cameraEnabled by viewModel.cameraEnabled.collectAsState()
    val voice by viewModel.companionVoice.collectAsState()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Privacy & Security", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(24.dp))
            PrivacyItem("Microphone", "Required for push-to-talk. Never enabled silently.",
                micEnabled, Icons.Default.Mic, viewModel::setMicEnabled)
            PrivacyItem("Camera", "Off by default. Only face position is used, to guide the avatar's eyes; nothing is recorded.",
                cameraEnabled, Icons.Default.Camera, viewModel::setCameraEnabled)
            PrivacyItem("Companion Voice", "Mutes spoken responses.",
                voice, Icons.AutoMirrored.Filled.VolumeUp, viewModel::setCompanionVoice)
            Spacer(Modifier.height(16.dp))
            Text("Voice, camera, chat, memories, and API keys stay on this phone unless you turn on a cloud " +
                "brain or a LAN server in Settings. The on-device model never sends your words anywhere. " +
                "HTTP is only allowed on your own network; everything else must be HTTPS.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

@Composable
internal fun PrivacyItem(label: String, description: String, isEnabled: Boolean, icon: ImageVector, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null,
                tint = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(label, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = isEnabled, onCheckedChange = onToggle)
    }
}

// ── LLM (Android ui/companion/ModelManagerScreen.kt) ─────────────────────────────────────────────

private enum class LoadStatus { ACTIVE, STANDBY, NOT_CONFIGURED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelScene(viewModel: CompanionViewModel, onRemote: () -> Unit, onVoice: () -> Unit, onBack: () -> Unit) {
    val active by OnDeviceModel.active.collectAsState()
    var files by remember { mutableStateOf(OnDeviceModel.driver?.installedFiles().orEmpty().toSet()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val installed = active.fileName in files
    val config by viewModel.config.collectAsState()
    val onDevice = config.kind == ProviderKind.ON_DEVICE
    val ramGb = DeviceInfo.ramBytes() / 1_073_741_824.0
    fun useForChat(option: com.meowmba.shared.agent.OnDeviceModelOption) {
        OnDeviceModel.choose(option)
        viewModel.onDraftChange(ProviderConfig(kind = ProviderKind.ON_DEVICE, model = option.id, companionName = config.companionName))
        viewModel.saveSettings(); status = "${option.name} is answering chat"
    }
    fun download(option: com.meowmba.shared.agent.OnDeviceModelOption) {
        val driver = OnDeviceModel.driver ?: return
        OnDeviceModel.choose(option)
        downloading = option.id; progress = 0f; status = null
        driver.install({ progress = it }, {
            files = driver.installedFiles().toSet(); downloading = null; progress = null; useForChat(option)
        }, { downloading = null; progress = null; status = it })
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopAppBar(title = { Text("LLM", fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close LLM") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                HardwareStatusCard()
                Spacer(Modifier.height(16.dp))
                VoiceRecognitionEntryCard(onVoice)
                Spacer(Modifier.height(24.dp))
                Text("Choose your AI", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text("Tap a card to manage its models and connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                LoadedModelCard(
                    icon = { Icon(Icons.Default.PhoneIphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title = "Local · on this phone", primary = active.name,
                    details = listOf("Runtime: llama.cpp · Metal", "${active.sizeLabel} · 2,048-token context · 256 reply tokens",
                        if (installed) "File: present on disk" else "File: NOT found on disk"),
                    status = when { !installed -> LoadStatus.NOT_CONFIGURED; onDevice -> LoadStatus.ACTIVE; else -> LoadStatus.STANDBY },
                    actionLabel = if (installed) (if (onDevice) "In use for chat" else "Tap to use for chat") else "Download below to use offline",
                    onClick = { if (installed && !onDevice) useForChat(active) },
                ) {
                    status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                    Text("Your prompts stay on the phone in on-device mode. The model is released from memory after each reply.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                Text("On-device models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Downloaded once over Wi-Fi, then private and offline. This iPhone has ${(ramGb * 10).toLong() / 10.0} GB of memory.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                com.meowmba.shared.agent.OnDeviceCatalog.options.forEach { option ->
                    val have = option.fileName in files
                    val inUse = onDevice && have && option.id == active.id
                    val tight = ramGb + 0.5 < option.minRamGb
                    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (inUse) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(option.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(option.sizeLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(option.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (tight) Text("Needs about ${option.minRamGb} GB of memory; it may not load on this iPhone.",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            if (downloading == option.id) progress?.let {
                                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                Text("Downloading ${(it * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when {
                                    inUse -> Text("In use for chat", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(vertical = 10.dp))
                                    have -> Button(onClick = { useForChat(option) }) { Text("Use for chat") }
                                    else -> Button(enabled = downloading == null && OnDeviceModel.driver != null,
                                        onClick = { download(option) }) { Text("Download") }
                                }
                                if (have && !inUse) TextButton(onClick = {
                                    OnDeviceModel.driver?.delete(option.fileName)
                                    files = OnDeviceModel.driver?.installedFiles().orEmpty().toSet()
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                NeedleCard()
                Spacer(Modifier.height(12.dp))
                val remoteConfigured = !onDevice && config.baseUrl.isNotBlank()
                LoadedModelCard(
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    title = "Network · cloud or your own server",
                    primary = if (remoteConfigured) config.model.ifBlank { "Default model" } else "Not set up",
                    details = if (remoteConfigured) listOf("Endpoint: ${config.baseUrl}", "Protocol: ${config.kind.name}",
                        if (config.apiKey.isBlank()) "Key: none" else "Key: saved on this phone") else
                        listOf("OpenRouter, OpenAI, Claude, Ollama, llama.cpp or any compatible server"),
                    status = if (remoteConfigured) LoadStatus.ACTIVE else LoadStatus.NOT_CONFIGURED,
                    actionLabel = "Manage models, keys & endpoints", onClick = onRemote,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HardwareStatusCard() {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("System Resources", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            val gb = DeviceInfo.ramBytes() / 1_073_741_824.0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Fact("RAM", "${(gb * 10).toLong() / 10}.${(gb * 10).toLong() % 10} GB")
                Fact("CPU", "${DeviceInfo.cpuCores()} Cores")
                Fact(DeviceInfo.osName(), DeviceInfo.osVersion())
                Fact("ARCH", if (DeviceInfo.is64Bit()) "64-bit" else "32-bit")
            }
            Spacer(Modifier.height(12.dp))
            val npu = DeviceInfo.hasNeuralAccelerator()
            Text(if (npu) "✅ Neural Engine" else "— NPU", fontSize = 11.sp,
                color = if (npu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) = Column {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    Text(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
}

@Composable
private fun VoiceRecognitionEntryCard(onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Voice Recognition", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text("Speech-to-text: Apple Speech (on-device)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
        }
    }
}

@Composable
private fun LoadedModelCard(icon: @Composable () -> Unit, title: String, primary: String, details: List<String>,
                            status: LoadStatus, onClick: () -> Unit = {}, actionLabel: String = "Tap to select",
                            settings: (@Composable ColumnScope.() -> Unit)? = null) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (status == LoadStatus.ACTIVE) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
        Column {
            Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    icon()
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val (label, color) = when (status) {
                        LoadStatus.ACTIVE -> "ACTIVE" to MaterialTheme.colorScheme.primary
                        LoadStatus.STANDBY -> "STANDBY" to MaterialTheme.colorScheme.tertiary
                        LoadStatus.NOT_CONFIGURED -> "NOT CONFIGURED" to MaterialTheme.colorScheme.error
                    }
                    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp), border = null) {
                        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(primary, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(actionLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                details.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            }
            settings?.let {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = it)
            }
        }
    }
}

/** Remote provider form (presets, protocol, endpoint, model, key), in the Settings chrome. */
@Composable
internal fun RemoteModelScene(viewModel: CompanionViewModel, onBack: () -> Unit) {
    val draft by viewModel.draft.collectAsState()
    val status by viewModel.statusLine.collectAsState()
    val configured by viewModel.config.collectAsState()
    SettingsPage("AI & models", onBack) {
        Text("Currently active: ${configured.describe()}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("Endpoint preset", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        ProviderPresets.ALL.forEach { preset ->
            Row(Modifier.fillMaxWidth().clickable { viewModel.onDraftChange(draft.withPreset(preset)) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = draft.baseUrl == preset.baseUrl, onClick = { viewModel.onDraftChange(draft.withPreset(preset)) })
                Text(preset.label, Modifier.weight(1f))
                preset.keyUrl?.let { url -> TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text("Get key", fontSize = 13.sp); androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp)) } }
            }
        }
        Row(Modifier.fillMaxWidth().clickable {
            viewModel.onDraftChange(draft.copy(kind = ProviderKind.TOTO_LION, baseUrl = "", model = "lion", apiKey = ""))
        }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = draft.kind == ProviderKind.TOTO_LION, onClick = {
                viewModel.onDraftChange(draft.copy(kind = ProviderKind.TOTO_LION, baseUrl = "", model = "lion", apiKey = ""))
            })
            Text("Toto Lion (pinned reply-only)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text("Protocol", style = MaterialTheme.typography.titleMedium)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderKind.entries.filter { it != ProviderKind.ON_DEVICE }.forEach { kind ->
                FilterChip(selected = draft.kind == kind, onClick = { viewModel.onDraftChange(draft.copy(kind = kind, apiKey = if (kind == ProviderKind.TOTO_LION || draft.kind == ProviderKind.TOTO_LION) "" else draft.apiKey)) },
                    label = { Text(kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }) })
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(draft.baseUrl, { viewModel.onDraftChange(draft.copy(baseUrl = it.trim())) },
            label = { Text("Base URL") }, placeholder = { Text("https://openrouter.ai/api") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.model, { viewModel.onDraftChange(draft.copy(model = it.trim())) },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (draft.kind != ProviderKind.TOTO_LION) ModelBrowser(draft) { viewModel.onDraftChange(draft.copy(model = it)) }
        if (draft.kind == ProviderKind.TOTO_LION) {
            OutlinedTextField(draft.certSha256, { viewModel.onDraftChange(draft.copy(certSha256 = it.trim().lowercase())) },
                label = { Text("Pinned certificate SHA-256 (64 hex)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            var lionToken by remember { mutableStateOf("") }
            var lionMessage by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(lionToken, { lionToken = it }, label = { Text("Toto bearer token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Token is stored only in the device Keychain; it is never written to provider settings.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val token = lionToken.trim()
                    val store = DesktopBridge.secrets
                    if (!draft.isConfigured) lionMessage = "Enter a valid HTTPS endpoint and 64-character certificate pin."
                    else if (token.any { it.isWhitespace() || it.code < 32 }) lionMessage = "Token must not contain whitespace."
                    else if (store == null) lionMessage = "Secure token storage is unavailable."
                    else {
                        if (token.isNotBlank()) store.writeSecret("toto_lion_token", token)
                        val stored = store.readSecret("toto_lion_token")
                        if (stored.isNullOrBlank() || (token.isNotBlank() && stored != token)) lionMessage = "Token could not be saved securely."
                        else {
                            lionToken = ""; lionMessage = null
                            viewModel.onDraftChange(draft.copy(apiKey = "")); viewModel.saveSettings()
                        }
                    }
                }) { Text("Save Lion") }
                OutlinedButton(onClick = viewModel::testConnection) { Text("Test connection") }
            }
            lionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else OutlinedTextField(draft.apiKey, { viewModel.onDraftChange(draft.copy(apiKey = it.trim())) },
            label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        draft.keyHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ProviderPresets.ALL.firstOrNull { it.baseUrl == draft.baseUrl }?.let { preset ->
            if (preset.keyUrl != null) TextButton(onClick = { runCatching { uri.openUri(preset.keyUrl) } }) {
                Text("Get your ${preset.label} API key"); androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp))
            } else Text("${preset.label} runs on your own machine and needs no key.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(draft.companionName, { viewModel.onDraftChange(draft.copy(companionName = it)) },
            label = { Text("Companion name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.kind != ProviderKind.TOTO_LION) {
                Button(onClick = viewModel::saveSettings) { Text("Save") }
                OutlinedButton(onClick = viewModel::testConnection) { Text("Test connection") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Professional home (Android ui/companion/ProfessionalWorkspace.kt, phone width) ──────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfessionalScene(viewModel: CompanionViewModel, pose: com.meowmba.shared.companion.AvatarPose, mode: AvatarMode,
                               onOpen: (String) -> Unit, onMenu: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val state by viewModel.state.collectAsState()
    val listening by viewModel.listening.collectAsState()
    val micEnabled by viewModel.micEnabled.collectAsState()
    val cameraEnabled by viewModel.cameraEnabled.collectAsState()
    val voice by viewModel.companionVoice.collectAsState()
    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Meowmba", style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
                Text("A clear space to think and work.", color = colors.onSurfaceVariant)
            }
            IconButton(onClick = { onOpen("Avatar") }) { Icon(Icons.Default.Palette, "Customize appearance", tint = colors.onSurface) }
            IconButton(onClick = { onOpen("Settings") }) { Icon(Icons.Default.Settings, "Settings", tint = colors.onSurface) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(136.dp).clip(RoundedCornerShape(28.dp)).background(colors.surfaceVariant)
                .clickable { viewModel.setMicEnabled(!micEnabled) }) {
                LumenAvatar(pose, Modifier.fillMaxSize(), mode)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.interactionState.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                Text("Ready when you are.", color = colors.onSurfaceVariant)
                TextButton(onClick = { onOpen("Setup") }) { Text("Guided setup") }
            }
        }
        state.errorMessage?.let { Text(it, color = colors.error) }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("How can I help?", style = MaterialTheme.typography.headlineMedium)
                Text("Start a conversation, or connect an app.", color = colors.onSurfaceVariant)
                WorkspaceAction("Ask Meowmba anything…", Icons.AutoMirrored.Filled.Chat) { onOpen("Chat") }
                Button(onClick = viewModel::onMicClick, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (listening) "Stop listening" else "Talk to Meowmba")
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = micEnabled, onClick = { viewModel.setMicEnabled(!micEnabled) },
                label = { Text(if (micEnabled) "Mic on" else "Mic off") })
            FilterChip(selected = cameraEnabled, onClick = { viewModel.setCameraEnabled(!cameraEnabled) },
                label = { Text(if (cameraEnabled) "Camera on" else "Camera off") })
            FilterChip(selected = voice, onClick = { viewModel.setCompanionVoice(!voice) },
                label = { Text(if (voice) "Voice on" else "Muted") })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpen("Connections") }, modifier = Modifier.weight(1f)) { Text("Connections") }
        }
        WorkspaceAction("Notes", Icons.Default.Description) { onOpen("Notes") }
        WorkspaceAction("All tools and apps", Icons.Default.Apps, onMenu)
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun WorkspaceAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f))
    }
}

// ── Not yet on iOS ───────────────────────────────────────────────────────────────────────────────

/** What each Android-only destination needs, so the page says why rather than just "unavailable". */
private fun unavailableReason(name: String): String = when (name) {
    "Computer", "Computer & task tools", "Tools", "Agents", "Projects", "Connections" ->
        "This runs through the Meowmba desktop bridge on your computer. Pairing the bridge from iPhone is not built yet."
    "Files" -> "Browsing files on your computer needs the desktop bridge, which is not paired from iPhone yet."
    "Vending", "Robot" -> "This controls Meowmba hardware over Bluetooth, which the iOS app does not support yet."
    "Auto" -> "Driving and vehicle features rely on Android Auto and background sensors that iOS does not offer apps."
    "Weather" -> "Weather is not connected in this iOS build yet."
    "Brain" -> "The neural brain monitor is not ported to iOS yet."
    "Images", "Music & video" -> "Media generation is not connected in this iOS build yet."
    "Backup & updates" -> "Use Export JSON in Memory to back up memories. Keys stay on this phone."
    "Help & feedback" -> "This is an early iOS build. Report problems to the Meowmba team."
    else -> "This Android feature has not been connected to iOS yet."
}

@Composable
internal fun UnavailableScene(name: String, onBack: () -> Unit) {
    SettingsPage(name.ifBlank { "Tools" }, onBack) {
        Spacer(Modifier.height(12.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not on iPhone yet", style = MaterialTheme.typography.titleMedium)
                Text(unavailableReason(name), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Nothing was changed.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Chat's Terminal tab: Android connects a local shell or SSH; iOS has neither without the bridge. */
@Composable
internal fun TerminalUnavailable(modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Terminal", style = MaterialTheme.typography.titleSmall)
        }
        Text("iPhone apps cannot open a local shell. The terminal will connect through the Meowmba desktop bridge once it can be paired from iOS.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Loads the provider's full model list; search it and tap to fill in the model. */
@Composable
private fun ModelBrowser(config: ProviderConfig, onPick: (String) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var models by remember(config.baseUrl, config.kind) { mutableStateOf<List<com.meowmba.shared.agent.OpenRouterModel>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = !loading, onClick = {
            loading = true; problem = null
            scope.launch {
                com.meowmba.shared.agent.ModelDirectory.list(config)
                    .onSuccess { models = it; if (it.isEmpty()) problem = "The provider returned no models." }
                    .onFailure { problem = it.message }
                loading = false
            }
        }) { Text(if (loading) "Loading models…" else if (models == null) "Browse all models" else "Refresh model list") }
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    val list = models ?: return
    OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${list.size} models…") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    if (list.any { it.free }) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(freeOnly, { freeOnly = it }); Spacer(Modifier.width(8.dp)); Text("Free models only", fontSize = 13.sp)
    }
    val shown = list.filter { (!freeOnly || it.free) && (query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true)) }
    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        shown.take(300).forEach { m ->
            Row(Modifier.fillMaxWidth().clickable { onPick(m.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = config.model == m.id, onClick = { onPick(m.id) })
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontSize = 14.sp, maxLines = 1)
                    Text(m.id, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (m.free) Text("FREE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (m.vision) Text(" · VISION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        if (shown.size > 300) Text("Showing 300 of ${shown.size}. Search to narrow.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings › Phone & device: home-screen options iOS allows, and what Meow may do on the phone. */
@Composable
internal fun DeviceSettingsScene(viewModel: CompanionViewModel, onBack: () -> Unit, onPrivacy: () -> Unit) {
    val acting by viewModel.deviceActions.collectAsState()
    var calendarNote by remember { mutableStateOf<String?>(null) }
    val open = { url: String -> com.meowmba.shared.platform.PlatformDrivers.apps?.open(url) {}; Unit }
    SettingsPage("Phone & device", onBack) {
        Spacer(Modifier.height(8.dp))
        Text("Home screen", style = MaterialTheme.typography.titleMedium)
        Text("iPhone does not let any app replace the Home Screen the way Android launchers do. These get you closest:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        HowTo("Action Button", "Settings › Action Button › Shortcut or Controls › Open App › Meowmba. One press opens Meow.")
        HowTo("Back Tap", "Settings › Accessibility › Touch › Back Tap › Double Tap › choose a Shortcut that opens Meowmba.")
        HowTo("Dock or first page", "Hold the Meowmba icon, drag it into the Dock, and keep it alone on your first Home Screen page.")
        HowTo("Guided Access (stay in Meowmba)", "Settings › Accessibility › Guided Access, then triple-click the side button in Meowmba. The phone stays in Meowmba until you end it.")
        OutlinedButton(onClick = { open("app-settings:") }, modifier = Modifier.fillMaxWidth()) { Text("Open iPhone Settings") }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Launcher mode", style = MaterialTheme.typography.titleMedium)
        val launcher by com.meowmba.shared.platform.LauncherMode.enabled.collectAsState()
        val kiosk by com.meowmba.shared.platform.LauncherMode.kiosk.collectAsState()
        var lockNote by remember { mutableStateOf<String?>(null) }
        PrivacyItem("Use Meowmba as home", "Keeps the screen awake and full-screen while Meowmba is open, like a docked home screen.",
            launcher, Icons.Default.PhoneIphone, com.meowmba.shared.platform.LauncherMode::setEnabled)
        PrivacyItem("Kiosk lock", "Turns off swipes so nobody wanders off; the Menu button still works. Hold the top-left corner for 3 seconds to leave.",
            kiosk, Icons.Default.Security) { on ->
            com.meowmba.shared.platform.LauncherMode.setKiosk(on)
            com.meowmba.shared.platform.LauncherMode.display?.requestGuidedAccess(on) { locked ->
                lockNote = if (!on) null else if (locked) "iPhone is locked to Meowmba." else
                    "To lock the phone to Meowmba too, triple-click the side button and start Guided Access (Settings › Accessibility › Guided Access)."
            }
        }
        lockNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        val charger by com.meowmba.shared.platform.ChargerAvatar.enabled.collectAsState()
        PrivacyItem("Charger avatar", "Plugging in (any charger, including MagSafe) with Meow open shows your avatar full screen, once per charge.",
            charger, Icons.Default.PhoneIphone, com.meowmba.shared.platform.ChargerAvatar::setEnabled)
        Text("To come back to Meowmba automatically: Shortcuts › Automation › App › “Is Closed” (pick your apps) › Open App: Meowmba, " +
            "set to Run Immediately. Add a “Charger is connected” automation to open it when you dock the phone. Siri: “Ask Meowmba …”.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text("Automation & control", style = MaterialTheme.typography.titleMedium)
        PrivacyItem("Let Meow act on this phone",
            "Open apps, run your Shortcuts, draft mail and messages (you tap send), and add reminders and calendar events when you ask.",
            acting, Icons.Default.Bolt, viewModel::setDeviceActions)
        Text("Try: “open Spotify”, “run shortcut Good Morning”, “remind me to call mom tomorrow at 5pm”, " +
            "“add dentist to my calendar Friday at 3pm”, “what's on my calendar today”, “email sam@example.com about dinner”.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("iOS does not allow apps to tap or read other apps' screens (Android's accessibility control). Shortcuts is the iPhone's " +
            "automation system: anything you build there, Meow can run by name — including Mail, Home, Focus and many third-party app actions.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { open("shortcuts://") }) { Text("Open Shortcuts") }
            OutlinedButton(onClick = {
                com.meowmba.shared.platform.PlatformDrivers.calendar?.requestAccess { ok, reason ->
                    calendarNote = if (ok) "Calendar and Reminders are connected." else reason
                } ?: run { calendarNote = "Calendar isn't available here." }
            }) { Text("Connect Calendar") }
        }
        calendarNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        OutlinedButton(onClick = onPrivacy, modifier = Modifier.fillMaxWidth()) { Text("Microphone, camera & privacy") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HowTo(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Needle 3: the tiny tool router that turns loose phrasing into phone actions. */
@Composable
private fun NeedleCard() {
    val needle = com.meowmba.shared.platform.Needle
    val status by needle.status.collectAsState()
    val enabled by needle.enabled.collectAsState()
    var progress by remember { mutableStateOf<Float?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { needle.refresh() }
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (status == "ready" && enabled) MaterialTheme.colorScheme.primary.copy(alpha = .6f)
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Needle 3 · action router", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("35 MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Cactus Compute's on-device tool model. It understands loose requests like " +
                "“ping 555 0100 that I'm late” or “put lunch with Sam on Friday at noon” and turns them into " +
                "reminders, events, drafts, shortcuts and apps. It does not chat.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Downloading ${(it * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    needle.driver == null -> Text("Not available in this build.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    status == "ready" -> {
                        Text(if (enabled) "Routing phone commands" else "Off", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = needle::setEnabled)
                        TextButton(onClick = { needle.driver?.delete(); needle.refresh() }) { Text("Delete") }
                    }
                    else -> Button(enabled = progress == null, onClick = {
                        note = null; progress = 0f
                        needle.driver?.download(onProgress = { progress = it }) { ok, message ->
                            progress = null; note = if (ok) "Needle 3 is ready." else message; needle.refresh()
                        }
                    }) { Text(if (status.startsWith("failed")) "Retry download" else "Download") }
                }
            }
            if (status.startsWith("failed")) Text(status.removePrefix("failed: "), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
    }
}
