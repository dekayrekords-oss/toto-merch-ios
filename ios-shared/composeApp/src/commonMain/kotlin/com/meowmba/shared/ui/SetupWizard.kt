package com.meowmba.shared.ui

import androidx.compose.material.icons.automirrored.filled.OpenInNew

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.agent.OnDeviceCatalog
import com.meowmba.shared.agent.OnDeviceModel
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.DeviceInfo
import com.meowmba.shared.platform.PlatformDrivers
import com.meowmba.shared.settings.ProviderConfig
import com.meowmba.shared.settings.ProviderKind
import com.meowmba.shared.settings.ProviderPresets
import com.meowmba.shared.ui.background.AnimationThumbnail
import com.meowmba.shared.ui.background.BackgroundKind
import com.meowmba.shared.ui.background.BackgroundSelection
import com.meowmba.shared.ui.background.availableBackgrounds
import com.meowmba.shared.ui.background.backgroundStore
import com.meowmba.shared.ui.lumen.AvatarAnimal
import com.meowmba.shared.ui.theme.Button
import com.meowmba.shared.ui.theme.FilterChip
import com.meowmba.shared.ui.theme.OutlinedButton
import com.meowmba.shared.ui.theme.Surface

private enum class SetupPage { Hi, DeviceCheck, Avatar, AvatarSize, Background, Mic, Ai, Home, Done }

internal object SetupState {
    const val DONE_KEY = "onboarding_done"
    fun needed(): Boolean = DefaultTextStorage.readText(DONE_KEY) != "true"
    fun markDone() = DefaultTextStorage.writeTextAtomic(DONE_KEY, "true")
}

/**
 * Android `SetupWizardRoute` (build 171) for iOS: the same pages and words, minus the Android-only
 * Termux, GitHub and server-pairing steps. Every choice is saved as it is made.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SetupWizard(viewModel: CompanionViewModel, avatarMode: AvatarMode, pose: com.meowmba.shared.companion.AvatarPose,
                         onFinished: () -> Unit, onOpenChat: () -> Unit, onRemoteModels: () -> Unit) {
    var page by remember { mutableStateOf(SetupPage.Hi) }
    val history = remember { mutableStateListOf<SetupPage>() }
    fun go(next: SetupPage) { history += page; page = next }
    fun back() { history.removeLastOrNull()?.let { page = it } }
    fun finish() { SetupState.markDone(); onFinished() }
    val appearance by AvatarAppearanceStore.state.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 28.dp, vertical = 20.dp)) {
            if (page != SetupPage.Done) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back, enabled = page != SetupPage.Hi) { Text("Back") }
                if (page == SetupPage.Avatar || page == SetupPage.AvatarSize) Text("Avatar setup",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton(onClick = ::finish) { Text("Finish later") }
            } else Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (page) {
                    SetupPage.Hi -> KidPage("Meet Toto.",
                        "Your robot’s avatar, voice and AI. Let’s set up how you talk together.",
                        "Let’s go", { go(SetupPage.DeviceCheck) })
                    SetupPage.DeviceCheck -> {
                        val gb = DeviceInfo.ramBytes() / 1_073_741_824.0
                        val suggested = OnDeviceCatalog.options.lastOrNull { it.minRamGb <= gb + 0.5 && it.recommended }
                            ?: OnDeviceCatalog.default
                        KidPage("Tuned for your phone",
                            "iPhone · iOS ${DeviceInfo.osVersion()} · ${(gb * 10).toLong() / 10.0} GB RAM · ${DeviceInfo.cpuCores()} processor cores\n\n" +
                                "${suggested.name} runs well here, with room for bigger on-device models if you want them.\n\n" +
                                "Meow checks the phone directly. Nothing is uploaded; every choice stays adjustable.",
                            "Continue", { go(SetupPage.Avatar) })
                    }
                    SetupPage.Avatar -> Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Pick your companion", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.fillMaxWidth().aspectRatio(1.7f).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)) {
                            LumenAvatar(pose, Modifier.fillMaxSize(), avatarMode, magnification = appearance.scale)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AvatarMode.entries.filter { it != AvatarMode.OFF }.forEach { mode ->
                                FilterChip(selected = avatarMode == mode, onClick = { viewModel.selectAvatarMode(mode) }, label = { Text(mode.title) })
                            }
                        }
                        if (avatarMode == AvatarMode.LUMEN) {
                            Text("Character", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AvatarAnimal.entries.forEach { animal ->
                                    FilterChip(selected = appearance.animal == animal,
                                        onClick = { AvatarAppearanceStore.update(appearance.copy(animal = animal)) }, label = { Text(animal.label) })
                                }
                            }
                        }
                        KidPrimaryButton("Next") { go(SetupPage.AvatarSize) }
                    }
                    SetupPage.AvatarSize -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("How big should Meow be?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)) {
                            LumenAvatar(pose, Modifier.fillMaxSize(), avatarMode, magnification = appearance.scale)
                        }
                        Text("${(appearance.scale * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                        Slider(appearance.scale, { AvatarAppearanceStore.update(appearance.copy(scale = it.coerceIn(.5f, 3f))) }, valueRange = .5f..3f)
                        KidPrimaryButton("Looks good") { go(SetupPage.Background) }
                    }
                    SetupPage.Background -> Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Set the scene", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text("A quiet backdrop or a little atmosphere. Change it any time from the menu.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val selection by backgroundStore.selection.collectAsState()
                        OutlinedButton(onClick = { backgroundStore.select(selection.copy(kind = BackgroundKind.NONE, value = "")) },
                            modifier = Modifier.fillMaxWidth()) { Text(if (selection.kind == BackgroundKind.NONE) "● Plain background" else "Plain background") }
                        availableBackgrounds.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { anim ->
                                    val chosen = selection.kind == BackgroundKind.ANIMATION && selection.value == anim.id
                                    Column(Modifier.weight(1f).clickable {
                                        backgroundStore.select(BackgroundSelection(BackgroundKind.ANIMATION, anim.id, selection.dimPercent))
                                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(1.65f).clip(RoundedCornerShape(12.dp))) {
                                            AnimationThumbnail(anim, Modifier.fillMaxSize())
                                        }
                                        Text(anim.label, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                                            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        KidPrimaryButton("Next") { go(SetupPage.Mic) }
                    }
                    SetupPage.Mic -> KidPage("Can we use the mic and camera?",
                        "Talking needs the mic. Face-following needs the camera. You can say no.",
                        "Allow", {
                            val next = { go(SetupPage.Ai) }
                            viewModel.setMicEnabled(true)
                            val speech = PlatformDrivers.speechIn
                            val askCamera = { PlatformDrivers.face?.requestPermission { _, _ -> next() } ?: next() }
                            if (speech != null) speech.requestPermission { _, _ -> askCamera() } else askCamera()
                        }, "Not now", { go(SetupPage.Ai) })
                    SetupPage.Ai -> SetupAiPage(viewModel, onNext = { go(SetupPage.Home) }, onRemote = onRemoteModels)
                    SetupPage.Home -> HomeLauncherSetup(onContinue = { go(SetupPage.Done) })
                    SetupPage.Done -> KidPage("Make yourself at home.",
                        "Toto opens on the avatar. Swipe for chat or open the small settings control for voice, models and memory. You can continue setup anytime.",
                        "Meet my avatar", ::finish, "Chat", { finish(); onOpenChat() })
                }
            }
        }
    }
}

/** Android `SetupProvidersPage`: on this phone (with the model list) or a cloud provider. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupAiPage(viewModel: CompanionViewModel, onNext: () -> Unit, onRemote: () -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    val active by OnDeviceModel.active.collectAsState()
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(OnDeviceModel.driver?.installedFiles().orEmpty().toSet()) }
    val config by viewModel.config.collectAsState()
    Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connect your AI", style = MaterialTheme.typography.headlineMedium)
        Text("Choose where Meow thinks, or do this later in LLM. On this phone is private and works offline; cloud models are smarter but need a key.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("local" to "On this phone") + ProviderPresets.ALL.map { it.label to it.label }).forEach { (id, label) ->
                FilterChip(selected == id, { selected = id }, { Text(label) })
            }
        }
        when (val provider = selected) {
            null -> Unit
            "local" -> {
                val gb = DeviceInfo.ramBytes() / 1_073_741_824.0
                OnDeviceCatalog.options.filter { it.minRamGb <= gb + 0.5 }.forEach { option ->
                    val have = option.fileName in files
                    Row(Modifier.fillMaxWidth().clickable(enabled = progress == null) {
                        OnDeviceModel.choose(option)
                        val use = {
                            viewModel.onDraftChange(ProviderConfig(kind = ProviderKind.ON_DEVICE, model = option.id, companionName = config.companionName))
                            viewModel.saveSettings(); status = "${option.name} is ready."
                        }
                        if (have) use() else OnDeviceModel.driver?.let { d ->
                            progress = 0f; status = null
                            d.install({ progress = it }, { progress = null; files = d.installedFiles().toSet(); use() },
                                { progress = null; status = it })
                        }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text((if (option.id == active.id) "● " else "") + option.name, fontWeight = FontWeight.Bold)
                            Text("${option.sizeLabel} · ${option.description}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (have) "Use" else "Download", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    }
                }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()); Text("Downloading ${(it * 100).toInt()}% — keep the app open on Wi-Fi") }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            else -> {
                val preset = ProviderPresets.ALL.first { it.label == provider }
                val uri = androidx.compose.ui.platform.LocalUriHandler.current
                Text(if (preset.keyUrl != null) "1. Get an API key from ${preset.label}.\n2. Paste it on the next screen and pick a model from the full list."
                    else "${preset.label} runs on your own computer and needs no key. Enter its address on the next screen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                preset.keyUrl?.let { url -> Button(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Get your ${preset.label} API key"); androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.padding(start = 4.dp).size(16.dp)) } }
                OutlinedButton(onClick = { viewModel.onDraftChange(config.withPreset(preset)); onRemote() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up ${preset.label}")
                }
            }
        }
        KidPrimaryButton("Continue setup", onNext)
        TextButton(onClick = onNext) { Text("Set up AI later") }
    }
}

@Composable
private fun KidPage(title: String, body: String, primary: String, onPrimary: () -> Unit,
                    secondary: String? = null, onSecondary: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        KidPrimaryButton(primary, onPrimary)
        if (secondary != null && onSecondary != null) OutlinedButton(onClick = onSecondary,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(secondary, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun KidPrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/** Android HomeLauncherSetup (build 181), iOS style: iOS has no replaceable launcher, so this
 *  offers the closest thing — launcher mode, a charger avatar and a "come back to Meow" automation. */
@Composable
private fun HomeLauncherSetup(onContinue: () -> Unit) {
    val launcher by com.meowmba.shared.platform.LauncherMode.enabled.collectAsState()
    val charger by com.meowmba.shared.platform.ChargerAvatar.enabled.collectAsState()
    Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Make Meow your home screen?", style = MaterialTheme.typography.headlineMedium)
        Text("Optional. iPhone doesn't let apps replace the Home Screen, but Meow can act like one while it's open.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use Meowmba as home", style = MaterialTheme.typography.titleMedium)
                Text("Screen stays awake and full-screen while Meow is open.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(launcher, com.meowmba.shared.platform.LauncherMode::setEnabled)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show my avatar on the charger", style = MaterialTheme.typography.titleMedium)
                Text("When you plug in (any charger, including MagSafe) with Meow open, your avatar takes the screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(charger, com.meowmba.shared.platform.ChargerAvatar::setEnabled)
        }
        Text("To open Meow by itself when you dock the phone: Shortcuts › Automation › Charger › Is Connected › Open URL “meowmba://avatar”, Run Immediately. Add Meowmba's widget to your Home Screen for one-tap Talk and Chat.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onContinue) { Text(if (launcher || charger) "Continue" else "Not now") }
    }
}
