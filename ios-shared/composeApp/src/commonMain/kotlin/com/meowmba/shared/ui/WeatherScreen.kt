package com.meowmba.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.meowmba.shared.ui.theme.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.weather.MoonPhase
import com.meowmba.shared.weather.TimeOfDay
import com.meowmba.shared.weather.WeatherCondition
import com.meowmba.shared.weather.WeatherNow

/**
 * The Weather page in the ecosystem grid.
 *
 * The weather *card* is a glance — it appears when the companion talks about the sky and it
 * goes away again. Anything you configure needs somewhere that stays put, which is here:
 * the current reading, the moon, the ambient switch, and the controls for looking at a sky
 * that is not the one outside right now.
 */
@Composable
fun WeatherScreen(
    weather: WeatherNow?,
    ambientEnabled: Boolean,
    onAmbientChange: (Boolean) -> Unit,
    previewTimeOfDay: TimeOfDay?,
    onPreviewTimeOfDay: (TimeOfDay?) -> Unit,
    onCycleCondition: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    location: String = "",
    status: String? = null,
    onLocationChange: (String) -> Unit = {},
) {
    var locationDraft by rememberSaveable(location) { mutableStateOf(location) }
    val shown = weather ?: PLACEHOLDER
    val clockTimeOfDay = rememberTimeOfDay(shown.isDay)
    val effectiveTimeOfDay = if (shown.isDay) TimeOfDay.DAY else TimeOfDay.NIGHT
    val moon = rememberMoonPhase()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Weather",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, "Refresh", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            // Live scene strip: the same renderer the companion screen uses, at full
            // strength. Tapping it steps the condition, which is how you audit an animation
            // without waiting for that weather to happen.
            if (weather != null) Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                WeatherScene(
                    condition = shown.condition,
                    isDay = effectiveTimeOfDay.isLight,
                    timeOfDay = effectiveTimeOfDay,
                    moonPhase = moon,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(modifier = Modifier.align(Alignment.CenterStart).padding(12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = .94f), MaterialTheme.shapes.medium).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${shown.temperatureF}",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text("°", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp)
                    }
                    Text(
                        "${shown.condition.emoji}  ${shown.condition.label}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    )
                    shown.place?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (weather != null) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("High", "${shown.highF}°")
                Stat("Low", "${shown.lowF}°")
                Stat("Feels", "${shown.feelsLikeF}°")
                Stat("Humidity", "${shown.humidityPct}%")
                Stat("Wind", "${shown.windMph} mph")
            }

            if (weather == null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No current reading. Choose a city below or use device location, then refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Tonight")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(MoonPhase.emoji(moon), fontSize = 30.sp)
                Spacer(Modifier.padding(horizontal = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(MoonPhase.label(moon), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                    Text(
                        "${(MoonPhase.illumination(moon) * 100).toInt()}% lit · " +
                            "day ${(moon * 29.53f).toInt()} of 29",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Settings")
            OutlinedTextField(value = locationDraft, onValueChange = { locationDraft = it.take(160) },
                label = { Text("Weather location") }, placeholder = { Text("City, state, country") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { onLocationChange(locationDraft.trim()) }, enabled = locationDraft.trim().length >= 2) { Text("Use this city") }
                TextButton(onClick = {
                    // iOS asks for location permission on the first device-location fetch.
                    locationDraft = ""; onLocationChange(""); onRefresh()
                }) { Text("Use device location") }
            }
            Text(if (location.isBlank()) "Using device location" else "Selected: $location", style = MaterialTheme.typography.bodySmall)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Always show weather",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Keeps the animation playing all the time, matching the real " +
                            "conditions and the time of day. Refreshes every 15 minutes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = ambientEnabled,
                    onCheckedChange = onAmbientChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onSurface,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            Spacer(Modifier.height(24.dp))

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
    }
}

@Composable
private fun TimeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Shown before the first fetch lands, so the page is never an empty rectangle. */
private val PLACEHOLDER = WeatherNow(
    condition = WeatherCondition.CLEAR,
    temperatureF = 72, feelsLikeF = 72, highF = 84, lowF = 61,
    humidityPct = 38, windMph = 7, isDay = true, place = null,
)
