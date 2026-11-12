package com.meowmba.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.ui.theme.Button
import com.meowmba.shared.ui.theme.Surface
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * Companion-home controls, ported from the Android app (build 171):
 * CompanionScreen.kt, MicKillButton.kt, CameraTrackingControl.kt, components/MicButton.kt,
 * CompanionWallClock.kt, PetCarePanel.kt (collapsed state) and AppRailTab.kt.
 * Sizes, colors, shapes and icons are the Android ones; only platform calls differ.
 */

/** Mirrors Android's `InteractionState` coloring for the big mic. */
enum class MicVisual { IDLE, LISTENING, THINKING }

/** Android `TextButton("Menu")`: a 48dp touch target, not just the glyphs. */
@Composable
internal fun CompanionMenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) { Text("Menu") }
}

/** Android `CompanionWallClock`: translucent surface chip, minute-aligned updates. */
@Composable
internal fun CompanionWallClock(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    var now by remember { mutableLongStateOf(PlatformTime.currentMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = PlatformTime.currentMillis()
            delay((60_000L - now % 60_000L).coerceIn(250L, 60_000L))
        }
    }
    val label = DateText.timeLine(now, PlatformTime.utcOffsetMinutes())
    Box(
        modifier
            .semantics { contentDescription = "Current time $label" }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            fontSize = 14.sp, style = MaterialTheme.typography.labelLarge)
    }
}

/** Android `MicKillButton`: the app-wide microphone privacy latch (not push-to-talk). */
@Composable
internal fun MicKillButton(enabled: Boolean, onEnabledChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
    IconToggleButton(
        checked = !enabled, onCheckedChange = { onEnabledChange(!it) },
        modifier = modifier.size(48.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape).semantics {
            stateDescription = if (enabled) "Microphone available" else "Microphone off"
        },
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surface, contentColor = tint,
            checkedContainerColor = MaterialTheme.colorScheme.errorContainer, checkedContentColor = tint),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (enabled) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = if (enabled) "Turn microphone off" else "Turn microphone on",
                modifier = Modifier.size(22.dp))
            Text(if (enabled) "MIC ON" else "MIC OFF", fontSize = 8.sp, lineHeight = 10.sp)
        }
    }
}

/** Android `CameraTrackingControl`: status label plus the camera resource toggle. */
@Composable
internal fun CameraTrackingControl(enabled: Boolean, statusLabel: String, onToggle: () -> Unit,
                                   modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (enabled) statusLabel else "Camera off", Modifier.widthIn(max = 138.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            if (enabled && onRetry != null) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Retry") }
            }
            IconButton(onClick = onToggle, modifier = Modifier.semantics { stateDescription = statusLabel }) {
                Icon(if (enabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = if (enabled) "Turn camera off" else "Turn camera on",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Android `components/MicButton`: 64dp tonal square in an 80dp slot. */
@Composable
internal fun MicButton(visual: MicVisual, isMicEnabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when {
        !isMicEnabled -> scheme.errorContainer to scheme.onErrorContainer
        visual == MicVisual.LISTENING -> scheme.secondaryContainer to scheme.onSecondaryContainer
        visual == MicVisual.THINKING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Box(modifier.size(80.dp), contentAlignment = Alignment.Center) {
        Button(onClick = onClick, modifier = Modifier.size(64.dp).semantics {
            stateDescription = if (!isMicEnabled) "Microphone off" else visual.name.lowercase()
        }, shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.buttonColors(containerColor = fill, contentColor = ink),
            border = BorderStroke(if (visual == MicVisual.LISTENING) 3.dp else 2.dp, scheme.outlineVariant),
            contentPadding = PaddingValues(0.dp)) {
            Icon(if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                contentDescription = "Microphone", modifier = Modifier.size(30.dp))
        }
    }
}

/** Android's bottom-end chat shortcut: bordered circular `IconButton`. */
@Composable
internal fun ChatShortcutButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick,
        modifier = modifier.border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Open chat", tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** One pinned destination on the edge rail. */
internal data class RailPin(val label: String, val icon: ImageVector)

/**
 * Android `AppRailTab`: the right-edge grip with three blue bars. Android pins other installed
 * apps; iOS cannot launch arbitrary apps, so the same strip holds in-app destinations. Same
 * geometry, colors and gestures: a leftward swipe or a tap on the grip toggles it.
 */
@Composable
internal fun AppRailTab(pins: List<RailPin>, onOpen: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.pointerInput(open) {
            var travelled = 0f
            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragEnd = { if (travelled < -36f) open = !open; travelled = 0f },
                onDragCancel = { travelled = 0f },
            ) { change, dragAmount -> change.consume(); travelled += dragAmount }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = open, enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it }) {
            val shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
            Column(
                Modifier.background(Color(0xCC0D1117), shape).border(1.dp, Color(0x3358A6FF), shape)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                pins.forEach { pin ->
                    Box(Modifier.padding(vertical = 7.dp).size(40.dp).clip(CircleShape)
                        .background(Color(0x1F58A6FF)).clickable { open = false; onOpen(pin.label) }
                        .semantics { contentDescription = "Open ${pin.label}" },
                        contentAlignment = Alignment.Center) {
                        Icon(pin.icon, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(22.dp))
                    }
                }
                if (pins.isEmpty()) Text("Pin a tool\nfrom the menu", color = Color(0xFF6E7681), fontSize = 10.sp,
                    lineHeight = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp).padding(bottom = 10.dp))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x1F58A6FF))
                    .clickable { open = false; onAdd() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = "Open the menu", tint = Color(0xFF58A6FF), modifier = Modifier.size(20.dp))
                }
            }
        }
        Box(
            Modifier.size(width = 22.dp, height = if (open) 96.dp else 72.dp)
                .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                .background(Color(0x990D1117)).clickable { open = !open }
                .semantics { contentDescription = if (open) "Close shortcuts" else "Open shortcuts" }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { Box(Modifier.size(width = 3.dp, height = 14.dp).clip(CircleShape).background(Color(0xAA58A6FF))) }
            }
        }
    }
}

/**
 * Android `homeSwipes`: down opens the menu, right-to-left opens chat, left-to-right goes to Flow,
 * up opens the app drawer. Observed on the Initial pass without consuming, so taps still reach
 * the avatar and controls underneath.
 */
internal fun Modifier.homeSwipes(onUp: () -> Unit, onDown: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit): Modifier = composed {
    // Always act on the latest screen: the gesture loop outlives recompositions.
    val up by androidx.compose.runtime.rememberUpdatedState(onUp)
    val downSwipe by androidx.compose.runtime.rememberUpdatedState(onDown)
    val left by androidx.compose.runtime.rememberUpdatedState(onLeft)
    val right by androidx.compose.runtime.rememberUpdatedState(onRight)
    pointerInput(Unit) {
        val threshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f; var dy = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break   // a tap handler may claim the release; the swipe still counts
                val d = change.positionChange(); dx += d.x; dy += d.y
                // A scroller or slider that took the drag owns it.
                if (change.isConsumed && (d.x != 0f || d.y != 0f)) return@awaitEachGesture
            }
            when {
                abs(dx) > abs(dy) * 1.4f && dx < -threshold -> left()
                abs(dx) > abs(dy) * 1.4f && dx > threshold -> right()
                abs(dy) > abs(dx) * 1.4f && dy > threshold -> downSwipe()
                abs(dy) > abs(dx) * 1.4f && dy < -threshold -> up()
            }
        }
    }
}

/**
 * A tap that stays a tap: unlike `detectTapGestures`, a finger that travels past the touch slop is a
 * swipe, not a tap, so swiping across the Desk or an avatar never "taps" what it started on.
 */
internal fun Modifier.tapOnly(onTap: androidx.compose.ui.input.pointer.PointerInputScope.(androidx.compose.ui.geometry.Offset) -> Unit): Modifier = composed {
    val tap by androidx.compose.runtime.rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travelled = 0f
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                travelled += change.positionChange().getDistance()
                if (travelled > viewConfiguration.touchSlop) return@awaitEachGesture
                if (!change.pressed) { this@pointerInput.tap(down.position); return@awaitEachGesture }
            }
        }
    }
}
