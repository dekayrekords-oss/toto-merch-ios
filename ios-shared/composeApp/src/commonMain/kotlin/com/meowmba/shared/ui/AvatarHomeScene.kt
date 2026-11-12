package com.meowmba.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meowmba.shared.companion.AvatarPose

/** State the companion home draws; everything else is a callback into the view model. */
internal data class CompanionHomeUi(
    val micEnabled: Boolean,
    val micVisual: MicVisual,
    val cameraEnabled: Boolean,
    val cameraStatus: String,
    val chatExpanded: Boolean,
    val speaking: Boolean = false,
    val offline: Boolean = false,
    val emotion: com.meowmba.shared.companion.CompanionEmotion = com.meowmba.shared.companion.CompanionEmotion.NEUTRAL,
)

/** In-app destinations on the right-edge rail (Android pins other apps here; see [AppRailTab]). */
private val railPins = listOf(
    RailPin("Flow", Icons.Default.ViewAgenda),
    RailPin("Memory", Icons.Default.Storage),
    RailPin("LLM", Icons.Default.Psychology),
    RailPin("Settings", Icons.Default.Settings),
)

/**
 * Companion home, layered as Android's `CompanionScreen` draws it: background → avatar → Care →
 * controls → Menu → clock. The avatar used to sit above Menu and Care and swallow their taps.
 */
@Composable
internal fun AvatarHomeScene(
    pose: AvatarPose, mode: AvatarMode, ui: CompanionHomeUi,
    onMenu: () -> Unit, onAvatar: () -> Unit, onChat: () -> Unit,
    onMicEnabled: (Boolean) -> Unit, onMic: () -> Unit, onCameraToggle: () -> Unit,
    onOpen: (String) -> Unit, onFlow: () -> Unit,
    care: @Composable (Modifier) -> Unit,
    sky: com.meowmba.shared.weather.WeatherNow? = null,
    atmosphere: com.meowmba.shared.weather.WeatherNow? = null,
    previewTimeOfDay: com.meowmba.shared.weather.TimeOfDay? = null,
    weatherCard: @Composable (Modifier) -> Unit = {},
) {
    val appearance by AvatarAppearanceStore.state.collectAsState()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        com.meowmba.shared.ui.background.CompanionBackground(Modifier.fillMaxSize(), sky, previewTimeOfDay)

        // 1. Avatar. Full-bleed like Android; its tap target sits *under* every control.
        LumenAvatar(pose, Modifier.fillMaxSize().safeDrawingPadding(), mode = mode,
            magnification = appearance.scale, onTap = onAvatar,
            thinking = ui.micVisual == MicVisual.THINKING, speaking = ui.speaking, offline = ui.offline,
            listening = ui.micVisual == MicVisual.LISTENING, emotion = ui.emotion)

        // Atmosphere in front of the avatar when ambient weather is on (Android draws it here, held back),
        // skipped when the live sky is already the background.
        val liveSkyIsBackground = com.meowmba.shared.ui.background.backgroundStore.selection.collectAsState().value.kind ==
            com.meowmba.shared.ui.background.BackgroundKind.LIVE_WEATHER
        if (!liveSkyIsBackground) atmosphere?.let { w ->
            WeatherScene(condition = w.condition, isDay = w.isDay,
                timeOfDay = previewTimeOfDay ?: rememberTimeOfDay(w.isDay),
                intensity = overlayIntensity(w.condition), modifier = Modifier.fillMaxSize())
        }

        // Care (PetCarePanel: a button that opens in place), TopStart with Android's 12dp / 64dp inset.
        care(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(horizontal = 12.dp, vertical = 64.dp))

        // 2. Controls layer, inside the safe area.
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            AppRailTab(railPins, onOpen = onOpen, onAdd = onMenu, modifier = Modifier.align(Alignment.CenterEnd))
            if (!ui.chatExpanded) MicButton(ui.micVisual, ui.micEnabled, onMic,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            ChatShortcutButton(onChat, Modifier.align(Alignment.BottomEnd).padding(16.dp))
            if (!ui.chatExpanded) {
                Row(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 64.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicKillButton(enabled = ui.micEnabled, onEnabledChange = onMicEnabled)
                    CameraTrackingControl(enabled = ui.cameraEnabled, statusLabel = ui.cameraStatus, onToggle = onCameraToggle)
                }
            }
        }

        // Menu shortcut: Android draws it after the controls, TopStart, safe area + 8dp.
        CompanionMenuButton(onMenu, Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(8.dp))

        // Top overlays (Android CompanionTopOverlays): clock, then the weather card below it.
        androidx.compose.foundation.layout.Column(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionWallClock()
            weatherCard(Modifier.padding(horizontal = 16.dp))
        }
    }
}
