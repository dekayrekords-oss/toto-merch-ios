package com.meowmba.shared.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.outlined.AutoAwesome
import com.meowmba.shared.ui.theme.FilledTonalButton
import androidx.compose.material3.Icon
import com.meowmba.shared.ui.theme.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.meowmba.shared.ui.theme.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meowmba.shared.behavior.PetCareState
import com.meowmba.shared.behavior.HabitatAction
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Premium compact care loop — meters, clear actions, form-aware reactions.
 * Scrolling keeps every control reachable at large text sizes.
 */
@Composable
internal fun PetCarePanel(
    care: PetCareState,
    motionPaused: Boolean = false,
    onCareChanged: (Boolean) -> Unit,
    onAction: (HabitatAction) -> Unit,
    onFeedback: (Boolean) -> Unit,
    onLearningChanged: (Boolean) -> Unit,
    learningEnabled: Boolean,
    feedbackNotice: String? = null,
    onResetLearning: () -> Unit,
    modifier: Modifier = Modifier,
    habitatMode: Boolean = true,
    onTrick: (String) -> Unit = {},
    formKey: String? = null,
    onHabitatMode: (Boolean) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(2_400)
            flash = null
        }
    }
    if (!expanded) {
        FilledTonalButton(onClick = { expanded = true }, modifier = modifier) {
            Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Care")
        }
        return
    }
    Surface(
        modifier = modifier.widthIn(max = 320.dp).heightIn(max = 440.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(if (habitatMode) "Play & care" else "Together", style = MaterialTheme.typography.titleMedium)
                    Text("Gentle needs · never dies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = false }) { Text("Close") }
            }
            Text("Avatar space", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !habitatMode, onClick = { onHabitatMode(false) },
                    label = { Text("Backdrop") }, modifier = Modifier.weight(1f))
                FilterChip(selected = habitatMode, onClick = { onHabitatMode(true) },
                    label = { Text("Habitat") }, modifier = Modifier.weight(1f))
            }
            if (motionPaused) {
                Text("Motion is resting while the phone settles. Care still works.", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Care mode", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = care.careEnabled, onCheckedChange = onCareChanged, modifier = Modifier.semantics { contentDescription = "Care mode" })
            }
            val reaction = flash ?: care.reaction(formKey)
            AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text(reaction, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            if (care.careEnabled) {
                NeedMeter("Food", care.food, MaterialTheme.colorScheme.tertiary)
                NeedMeter("Energy", care.energy, MaterialTheme.colorScheme.primary)
                NeedMeter("Mood", care.happiness, MaterialTheme.colorScheme.secondary)
                NeedMeter("Affection", care.affection, MaterialTheme.colorScheme.error)
                NeedMeter("Hygiene", care.hygiene, MaterialTheme.colorScheme.outline)
            } else {
                Text(
                    "Self-sufficient: needs stay full without care. You can still play together.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Gestures: hold to pet · double-tap to play", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tricks", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Peekaboo" to "peekaboo", "Tickle" to "fond_nuzzle", "Dance" to "pocket_groove").forEach { (label, clip) ->
                    FilledTonalButton(
                        onClick = {
                            onTrick(clip)
                            flash = "Trick sent — $label!"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text("Care actions", style = MaterialTheme.typography.labelLarge)
            val careActions = buildList {
                add(CareAction("Feed", Icons.Filled.Restaurant, HabitatAction.FEED))
                if (habitatMode) add(CareAction("Play", Icons.Filled.SportsEsports, HabitatAction.PLAY))
                add(CareAction("Rest", Icons.Filled.Bedtime, HabitatAction.REST))
                add(CareAction("Clean", Icons.Filled.Bathtub, HabitatAction.CLEAN))
                add(CareAction("Talk", Icons.Filled.ChatBubble, HabitatAction.TALK))
                if (habitatMode) {
                    add(CareAction("Drink", Icons.Filled.Restaurant, HabitatAction.DRINK))
                    add(CareAction("Explore", Icons.Outlined.AutoAwesome, HabitatAction.EXPLORE))
                }
            }
            careActions.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEach { action ->
                        FilledTonalButton(
                            onClick = {
                                onAction(action.habitat)
                                flash = when (action.habitat) {
                                    HabitatAction.FEED, HabitatAction.DRINK -> "Shared a snack."
                                    HabitatAction.PLAY -> "Play time!"
                                    HabitatAction.REST -> "Settling in for a rest."
                                    HabitatAction.CLEAN -> "Fresh and tidy."
                                    HabitatAction.TALK -> "Felt heard."
                                    else -> care.reaction(formKey)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(action.icon, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(action.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (habitatMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(onClick = { onAction(HabitatAction.READ) }, modifier = Modifier.weight(1f)) { Text("Read") }
                    FilledTonalButton(onClick = { onAction(HabitatAction.NUDGE) }, modifier = Modifier.weight(1f)) { Text("Nudge") }
                    FilledTonalButton(onClick = { onAction(HabitatAction.RELEASE) }, modifier = Modifier.weight(1f)) { Text("Put down") }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Learn preferences", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = learningEnabled, onCheckedChange = onLearningChanged, modifier = Modifier.semantics { contentDescription = "Learn preferences" })
            }
            Text("Teach which recent behaviors you enjoy.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onFeedback(true) }, enabled = learningEnabled, modifier = Modifier.weight(1f)) { Text("Like this") }
                TextButton(onClick = { onFeedback(false) }, enabled = learningEnabled, modifier = Modifier.weight(1f)) { Text("Less like this") }
            }
            feedbackNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onResetLearning) { Text("Reset learned preferences") }
        }
    }
}

private data class CareAction(val label: String, val icon: ImageVector, val habitat: HabitatAction)

@Composable
private fun NeedMeter(label: String, value: Float, color: androidx.compose.ui.graphics.Color) {
    val pct = percent(value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("$pct%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

private fun percent(value: Float): Int = if (value.isFinite()) (value.coerceIn(0f, 1f) * 100).roundToInt() else 100
