package com.meowmba.shared.ui.background

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import org.jetbrains.compose.resources.decodeToImageBitmap
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meowmba.shared.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Backdrops Android offers under Animated; weather-pinned scenes and habitats are separate. */
internal val availableBackgrounds = BackgroundAnimation.entries.filter { it.condition == null && !it.isPetEnvironment }

/** The bundled "Sunroom · photographic" still (Android `asset:///backgrounds/conservatory.png`). */
internal const val SUNROOM = "asset:conservatory"

/**
 * Android `BackgroundSelection` persisted as kind/value/dim. Older builds stored only an
 * animation id under `background_animation`; that is read once and carried forward.
 */
internal class BackgroundStore(private val storage: TextStorage = DefaultTextStorage) {
    val selection = MutableStateFlow(restore())

    private fun restore(): BackgroundSelection {
        val stored = storage.readText("background_kind")
        if (stored != null) return BackgroundSelection.parse(stored, storage.readText("background_value"), storage.readText("background_dim"))
        val legacy = BackgroundAnimation.byId(storage.readText("background_animation"))
        val dim = storage.readText("background_dim")
        return if (legacy != null) BackgroundSelection(BackgroundKind.ANIMATION, legacy.id, dim?.toIntOrNull() ?: 35)
        else BackgroundSelection(dimPercent = dim?.toIntOrNull()?.coerceIn(0, 100) ?: 35)
    }

    fun select(value: BackgroundSelection) {
        val bounded = value.copy(dimPercent = value.dimPercent.coerceIn(0, 90))
        storage.writeTextAtomic("background_kind", bounded.kind.name)
        storage.writeTextAtomic("background_value", bounded.value)
        storage.writeTextAtomic("background_dim", bounded.dimPercent.toString())
        selection.value = bounded
    }
}
internal val backgroundStore = BackgroundStore()

/** Draw-only state at 15 fps. The effect is cancelled when this scene leaves composition. */
internal class SceneClock {
    var elapsedMs by mutableStateOf(0L)
    fun phase(period: Int): Float = (elapsedMs % period).toFloat() / period
}
@Composable
internal fun rememberSceneClock(paused: Boolean, interacting: Boolean): SceneClock {
    val clock = remember { SceneClock() }
    LaunchedEffect(paused, interacting) {
        if (!paused) {
            var previous = withFrameNanos { it }
            while (true) {
                delay(if (interacting) 32L else 66L)
                val now = withFrameNanos { it }
                // Do not jump through an entire background suspension on resume.
                clock.elapsedMs += ((now - previous) / 1_000_000).coerceIn(0, 100)
                previous = now
            }
        }
    }
    return clock
}

@Composable
internal fun CompanionBackground(modifier: Modifier = Modifier, sky: com.meowmba.shared.weather.WeatherNow? = null,
                                 previewTimeOfDay: com.meowmba.shared.weather.TimeOfDay? = null) {
    val selection by backgroundStore.selection.collectAsState()
    AppBackground(selection, modifier, sky, previewTimeOfDay)
}

/** Android `AppBackground`: the chosen backdrop plus its dimming scrim. */
@Composable
internal fun AppBackground(selection: BackgroundSelection, modifier: Modifier = Modifier,
                           sky: com.meowmba.shared.weather.WeatherNow? = null,
                           previewTimeOfDay: com.meowmba.shared.weather.TimeOfDay? = null) {
    Box(modifier) {
        when (selection.kind) {
            BackgroundKind.NONE -> Unit
            // The real sky, at full strength behind the companion; a clear sky until the first reading.
            BackgroundKind.LIVE_WEATHER -> {
                val w = sky
                val condition = w?.condition ?: com.meowmba.shared.weather.WeatherCondition.CLEAR
                val isDay = w?.isDay ?: (com.meowmba.shared.weather.TimeOfDay.now().isLight)
                com.meowmba.shared.ui.WeatherScene(condition = condition, isDay = isDay,
                    timeOfDay = previewTimeOfDay ?: com.meowmba.shared.ui.rememberTimeOfDay(isDay), modifier = Modifier.fillMaxSize())
            }
            BackgroundKind.IMAGE -> ImageBackground(selection.value, Modifier.fillMaxSize())
            BackgroundKind.ANIMATION -> selection.animation?.let { AnimatedBackground(it, Modifier.fillMaxSize()) }
        }
        if (selection.kind != BackgroundKind.NONE && selection.dimPercent > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = selection.dimPercent / 100f)))
        }
    }
}

/** The Sunroom still or a picked photo, cropped to fill like Android's `ContentScale.Crop`. */
@Composable
internal fun ImageBackground(value: String, modifier: Modifier = Modifier) {
    var image by remember(value) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(value) {
        image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val bytes = if (value == SUNROOM) com.meowmba.shared.resources.Res.readBytes("files/backgrounds/conservatory.jpg")
                    else com.meowmba.shared.agent.ModelFile(value).readBytes()
                bytes?.decodeToImageBitmap()
            }.getOrNull()
        }
    }
    image?.let {
        androidx.compose.foundation.Image(it, contentDescription = null, modifier = modifier,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

/**
 * Android `BackgroundScreen`: Static · Animated · Habitats. Choices apply the moment they are
 * tapped and the screen stays open, because picking a wallpaper is a comparison task.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackgroundScene(onClose: () -> Unit, habitatMode: Boolean = false, onHabitatMode: (Boolean) -> Unit = {}) {
    val selection by backgroundStore.selection.collectAsState()
    val appearance by com.meowmba.shared.ui.AvatarAppearanceStore.state.collectAsState()
    var tab by remember { mutableStateOf(if (habitatMode) "Habitats" else if (selection.kind == BackgroundKind.ANIMATION) "Animated" else "Static") }
    val onSelect: (BackgroundSelection) -> Unit = backgroundStore::select
    val selectedName = when (selection.kind) {
        BackgroundKind.NONE -> "Plain background"
        BackgroundKind.IMAGE -> if (selection.value == SUNROOM) "Sunroom" else "Your picture"
        BackgroundKind.LIVE_WEATHER -> "Live weather"
        BackgroundKind.ANIMATION -> selection.animation?.label ?: "Background"
    }
    val tokens = com.meowmba.shared.ui.AvatarSetupTokens
    com.meowmba.shared.ui.theme.Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = tokens.page)) {
            Row(Modifier.fillMaxWidth().padding(top = tokens.compact), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Set the scene", style = MaterialTheme.typography.headlineSmall)
                    Text("Backgrounds & habitats", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "Close backgrounds")
                }
            }
            com.meowmba.shared.ui.SetupSection(selectedName, if (habitatMode) "Play habitat selected" else "Backdrop selected")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(tokens.compact)) {
                listOf("Static", "Animated", "Habitats").forEach { option ->
                    com.meowmba.shared.ui.theme.FilterChip(selected = tab == option, onClick = { tab = option }, label = { Text(option) })
                }
            }
            when (tab) {
                "Static" -> {
                    com.meowmba.shared.ui.SetupSection("Keep it simple", "A quiet space or a picture of your own.")
                    ChoiceTile("Sunroom · photographic", selection.value == SUNROOM && !habitatMode,
                        onClick = { onHabitatMode(false); onSelect(BackgroundSelection(BackgroundKind.IMAGE, SUNROOM, 10)) },
                        modifier = Modifier.fillMaxWidth()) {
                        ImageBackground(SUNROOM, Modifier.fillMaxSize())
                    }
                    ChoiceTile("Plain background", selection.kind == BackgroundKind.NONE && !habitatMode,
                        onClick = { onHabitatMode(false); onSelect(selection.copy(kind = BackgroundKind.NONE, value = "")) },
                        modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                            Text("Plain", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val picker = com.meowmba.shared.platform.PlatformDrivers.photos
                    com.meowmba.shared.ui.theme.Button(enabled = picker != null, onClick = {
                        picker?.pickImage { path ->
                            if (path != null) { onHabitatMode(false); onSelect(selection.copy(kind = BackgroundKind.IMAGE, value = path)) }
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = tokens.item)) {
                        Icon(androidx.compose.material.icons.Icons.Default.Image, contentDescription = null)
                        Text(if (selection.kind == BackgroundKind.IMAGE && selection.value != SUNROOM) "Choose another picture" else "Choose a picture",
                            modifier = Modifier.padding(start = tokens.compact))
                    }
                    if (selection.kind == BackgroundKind.IMAGE && selection.value.isNotBlank() && selection.value != SUNROOM) {
                        Box(Modifier.fillMaxWidth().padding(top = tokens.item).aspectRatio(1.6f).clip(RoundedCornerShape(tokens.corner))) {
                            ImageBackground(selection.value, Modifier.fillMaxSize())
                        }
                    }
                }
                "Animated" -> {
                    com.meowmba.shared.ui.SetupSection("A little atmosphere", "Scenery moves behind your full-size companion.")
                    ChoiceTile("Live weather", selection.kind == BackgroundKind.LIVE_WEATHER && !habitatMode,
                        onClick = { onHabitatMode(false); onSelect(selection.copy(kind = BackgroundKind.LIVE_WEATHER, value = "")) },
                        modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Text("Live sky", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Text("Live weather follows the real sky and time of day.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = tokens.item))
                    BackgroundChoiceGrid(availableBackgrounds, selection) { animation ->
                        onHabitatMode(false)
                        onSelect(selection.copy(kind = BackgroundKind.ANIMATION, value = animation.id))
                    }
                }
                else -> {
                    com.meowmba.shared.ui.SetupSection("Cartoon worlds", "Play with a portal, wake a moonflower, blow alien bubbles or launch a tiny rocket. Care is optional.")
                    BackgroundChoiceGrid(listOf(
                        BackgroundAnimation.CARTOON_PORTAL_ARCADE, BackgroundAnimation.CARTOON_MOON_GARDEN,
                        BackgroundAnimation.CARTOON_ALIEN_AQUARIUM, BackgroundAnimation.CARTOON_METEOR_GARAGE,
                        BackgroundAnimation.CARTOON_SNACK_LAB, BackgroundAnimation.CARTOON_LAUNDROMAT,
                        BackgroundAnimation.CARTOON_DINER, BackgroundAnimation.CARTOON_NOOK, BackgroundAnimation.CARTOON_GALLEY,
                    ), selection) { animation ->
                        onHabitatMode(true)
                        onSelect(selection.copy(kind = BackgroundKind.ANIMATION, value = animation.id))
                    }
                    com.meowmba.shared.ui.SetupSection("More places to play", "The original rooms, gardens and escapes are here too.")
                    BackgroundChoiceGrid(BackgroundAnimation.entries.filter { it.isPetEnvironment && !it.isCartoonEnvironment }, selection) { animation ->
                        onHabitatMode(true)
                        onSelect(selection.copy(kind = BackgroundKind.ANIMATION, value = animation.id))
                    }
                }
            }
            if (!habitatMode) {
                com.meowmba.shared.ui.SetupSection("Background dimming", "Make the scenery softer so your companion is easy to see.")
                Text("${selection.dimPercent}%", style = MaterialTheme.typography.titleMedium)
                var draft by remember(selection.dimPercent) { mutableStateOf(selection.dimPercent.toFloat().coerceIn(0f, 90f)) }
                Slider(value = draft, onValueChange = { draft = it }, valueRange = 0f..90f,
                    onValueChangeFinished = { onSelect(selection.copy(dimPercent = draft.toInt().coerceIn(0, 90))) },
                    modifier = Modifier.fillMaxWidth())
            }
            com.meowmba.shared.ui.SetupSizeControl(appearance.scale) { com.meowmba.shared.ui.AvatarAppearanceStore.update(appearance.copy(scale = it)) }
            Spacer(Modifier.height(tokens.section))
        }
    }
}

@Composable
private fun BackgroundChoiceGrid(animations: List<BackgroundAnimation>, selection: BackgroundSelection, onChoose: (BackgroundAnimation) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val tokens = com.meowmba.shared.ui.AvatarSetupTokens
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / (132f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
        Column(verticalArrangement = Arrangement.spacedBy(tokens.item)) {
            animations.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(tokens.item)) {
                    row.forEach { animation ->
                        ChoiceTile(animation.label, selection.kind == BackgroundKind.ANIMATION && selection.value == animation.id,
                            onClick = { onChoose(animation) }, modifier = Modifier.weight(1f)) {
                            AnimationThumbnail(animation, Modifier.fillMaxSize())
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A preview with its name under it, and a tick when it is the active choice. */
@Composable
private fun ChoiceTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                       preview: @Composable () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.65f).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(14.dp))) {
            preview()
            if (selected) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary).padding(2.dp)) {
                    Icon(androidx.compose.material.icons.Icons.Default.Check, contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(1.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal)
    }
}
