package com.meowmba.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.flow.*
import com.meowmba.shared.memory.DateText
import com.meowmba.shared.ui.flow.*
import kotlinx.coroutines.delay

/** Platform adapter for the Android Flow screen and Adjust sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidFlowScene(
    notes: List<FlowNote>,
    onKeep: (String) -> Unit,
    onDone: (String) -> Unit,
    onLater: (String) -> Unit,
    onAsk: (String) -> Unit,
    onMenu: () -> Unit,
    onDesk: () -> Unit,
    onChat: () -> Unit,
    onSettings: () -> Unit,
    micOn: Boolean,
    voice: FlowVoice,
    onMic: () -> Unit,
    onMicToggle: () -> Unit,
    signals: List<com.meowmba.shared.core.flow.Signal> = emptyList(),
    onAttach: () -> Unit = {},
    idleAvatar: String? = null,
    idleMinutes: Int = com.meowmba.shared.ui.flow.IdleAvatarOptions.DEFAULT_MINUTES,
    onIdleAvatar: (String, Int) -> Unit = { _, _ -> },
    northStars: com.meowmba.shared.core.northstar.NorthStars? = null,
    commentFor: (String) -> String? = { null },
    onComment: (item: FlowItem, text: String, done: (String) -> Unit) -> Unit = { _, _, done -> done("Saved.") },
    activeWork: Int = 0,
    onWork: ((String?) -> Unit)? = null,
) {
    val preferences = remember {
        FlowSettingsStore(object : FlowKeyValue {
            override fun getString(key: String) = DefaultTextStorage.readText(key)
            override fun putAll(values: Map<String, String?>) {
                values.forEach { (key, value) ->
                    if (value == null) DefaultTextStorage.delete(key)
                    else DefaultTextStorage.writeTextAtomic(key, value)
                }
            }
        })
    }
    var settings by remember { mutableStateOf(preferences.load()) }
    var now by remember { mutableStateOf(PlatformTime.currentMillis()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var commentItem by remember { mutableStateOf<FlowItem?>(null) }
    var commentBusy by remember { mutableStateOf(false) }
    var commentResult by remember { mutableStateOf<String?>(null) }
    val starViews = northStars?.views?.collectAsState()?.value.orEmpty()
    LaunchedEffect(Unit) {
        while (true) { now = PlatformTime.currentMillis(); delay(30_000) }
    }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val context by com.meowmba.shared.core.flow.FlowContextState.items.collectAsState()
    val snapshot = flowSnapshotFor(notes, now, signals, context)
    val night = FlowThemes.isNight(settings.eveningLook, WorkState.WORKING)
    val colors = FlowColors.from(FlowThemes.palette(settings.theme, night, settings.accent))
    MeowFlowHome(
        snapshot, settings, flowDate(now),
        onPrimary = { item ->
            val action = item.primary.id
            if (action.startsWith("track:")) runCatching { uriHandler.openUri(action.removePrefix("track:")) }
            else onDone(item.intentionId)
        },
        onLater = { onLater(it.intentionId) },
        onAdjust = { sheet = "adjust" },
        onTopBar = { sheet = "attention" },
        onAsk = { sheet = "compose" },
        onMic = onMic,
        onMenu = onMenu, onDesk = onDesk,
        voice = voice, micOn = micOn, onMicToggle = onMicToggle,
        onAttach = onAttach,
        northStar = if (northStars != null) ({
            Column {
                NorthStarLine(starViews.firstOrNull()) { sheet = "stars" }
                if (onWork != null) FlowLink(if (activeWork > 0) "Meow’s work · $activeWork active" else "Meow’s work", colors.ink2) { onWork(null) }
            }
        }) else null,
        onComment = { commentItem = it; commentResult = null; sheet = "comment" },
    )
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.paper, contentColor = colors.ink,
            dragHandle = null,
        ) {
            // Flow's sheets are paper: the app's dark Material theme would draw light text and fields on them.
            androidx.compose.material3.MaterialTheme(colorScheme = paperScheme(colors.ink)) {
            if (sheet == "stars" && northStars != null) {
                NorthStarSheet(northStars, onClose = { sheet = null }, onOpenSource = { key ->
                    if (key.startsWith("task:")) { sheet = null; onWork?.invoke(key.removePrefix("task:")) } else sheet = null
                })
            } else if (sheet == "access") {
                AccessSetupGuide(onClose = { sheet = null })
            } else if (sheet == "comment" && commentItem != null) {
                val item = commentItem!!
                FlowCommentSheet(item, commentFor(item.intentionId), commentBusy, commentResult,
                    onSubmit = { text -> commentBusy = true
                        onComment(item, text) { result -> commentBusy = false; commentResult = result } },
                    onSetup = { sheet = "access" }, onClose = { sheet = null })
            } else if (sheet == "attention") {
                com.meowmba.shared.ui.flow.AttentionSheet(com.meowmba.shared.ui.flow.needsAttention(snapshot), colors,
                    onPrimary = { item -> sheet = null; onDone(item.intentionId) },
                    onLater = { item -> onLater(item.intentionId) }, onClose = { sheet = null })
            } else if (sheet == "adjust") {
                FlowAdjustSheet(
                    settings, learnedHours = "Not enough information yet", night = night,
                    onChange = { settings = it; preferences.save(it) },
                    onDone = { sheet = null },
                    onAppSettings = { sheet = null; onSettings() },
                    idleAvatar = idleAvatar, idleMinutes = idleMinutes, onIdleAvatar = onIdleAvatar,
                    onNorthStars = if (northStars != null) ({ sheet = "stars" }) else null,
                    onAccessSetup = { sheet = "access" },
                    thinking = ThinkingStatus("Flow reads your calendar, reminders, weather and battery on this phone. Rules decide; nothing leaves the phone."),
                    modifier = Modifier.heightIn(max = 680.dp),
                )
            } else {
                Column(Modifier.fillMaxWidth().imePadding().padding(28.dp)) {
                    BasicText("Tell Meow", style = TextStyle(fontFamily = FlowFonts.serif,
                        fontSize = 26.sp, color = colors.ink))
                    OutlinedTextField(
                        draft, { draft = it }, Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text("What is on your mind?") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.ink, unfocusedTextColor = colors.ink,
                            focusedLabelColor = colors.ink2, unfocusedLabelColor = colors.ink2,
                            focusedBorderColor = colors.ink, unfocusedBorderColor = colors.rule,
                            cursorColor = colors.ink,
                        ),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onKeep(draft); draft = ""; sheet = null },
                            enabled = draft.isNotBlank()) { Text("Keep in Flow", color = colors.ink) }
                        TextButton(onClick = { onAsk(draft); draft = ""; sheet = null },
                            enabled = draft.isNotBlank()) { Text("Ask Meow", color = colors.ink) }
                        TextButton(onClick = { sheet = null }) { Text("Cancel", color = colors.ink2) }
                    }
                }
            }
            }
        }
    }
}

/** Material colours for text, fields and buttons drawn on Flow's paper, following its [ink]. */
@Composable
internal fun paperScheme(ink: androidx.compose.ui.graphics.Color): androidx.compose.material3.ColorScheme {
    val base = androidx.compose.material3.MaterialTheme.colorScheme
    return base.copy(primary = ink, onPrimary = androidx.compose.ui.graphics.Color.White,
        onSurface = ink, onSurfaceVariant = ink.copy(alpha = .72f), outline = ink.copy(alpha = .45f),
        outlineVariant = ink.copy(alpha = .25f), surfaceContainerHighest = ink.copy(alpha = .06f),
        secondaryContainer = ink.copy(alpha = .10f), onSecondaryContainer = ink)
}

private fun flowDate(millis: Long): String {
    val parts = DateText.weekdayMonthDay(millis, PlatformTime.utcOffsetMinutes()).split(", ", " ")
    return (parts.firstOrNull().orEmpty().take(3) + " " + parts.lastOrNull().orEmpty() + " " +
        parts.getOrNull(1).orEmpty().take(3) + " · " +
        DateText.timeLine(millis, PlatformTime.utcOffsetMinutes())).uppercase()
}

/** What a kept note is about, from its words: the Desk files people in the tray and money in the jar. */
internal fun guessKind(text: String): FlowKind {
    val t = text.lowercase()
    return when {
        Regex("""\$\s?\d|\b(invoice|bill|rent|pay|paid|refund|budget|tax|taxes)\b""").containsMatchIn(t) -> FlowKind.MONEY
        Regex("""^(call|text|email|e-mail|reply|message|ask|tell|ping|write to|thank|meet)\b|\bback to\b|\b(call|text|email) \w+ back\b""").containsMatchIn(t) -> FlowKind.PEOPLE
        Regex("""^(pick up|buy|drop off|return|get|order|renew|book)\b""").containsMatchIn(t) -> FlowKind.ERRAND
        else -> FlowKind.OTHER
    }
}

/** Set once by the view model so every snapshot (Flow, idle ribbon) shows goal links. */
internal var northStarsForSnapshot: com.meowmba.shared.core.northstar.NorthStars? = null

/** Kept notes as Flow items; shared by Flow and the idle avatar's arrival ribbon. */
internal fun flowSnapshotFor(notes: List<FlowNote>, now: Long, signals: List<com.meowmba.shared.core.flow.Signal>,
    context: List<FlowItem> = com.meowmba.shared.core.flow.FlowContextState.items.value): FlowSnapshot {
    val all = allFlowItems(notes, now, context)
    return FlowSnapshot(
        at = now, signals = signals, main = all.firstOrNull(), more = all.drop(1).take(3),
        interrupt = null, evening = false, judgments = emptyList(),
    )
}

/** Every open Flow item, most pressing first (the Desk counts all of them; Flow shows the first four). */
internal fun allFlowItems(notes: List<FlowNote>, now: Long,
    context: List<FlowItem> = com.meowmba.shared.core.flow.FlowContextState.items.value): List<FlowItem> {
    val packageNote = Regex("^Package on its way \\((\\w+)\\) · (\\S+)$")
    val items = notes.filter { !it.done && it.snoozedUntil <= now }.map { note ->
        // A shared shipping label (Android: "you shared a tracking number") offers Track, not Done.
        val pkg = packageNote.find(note.text)
        if (pkg != null) FlowItem(
            intentionId = note.id, kind = FlowKind.HOME, headline = "Package on its way (${pkg.groupValues[1]})",
            because = "Tracking ${pkg.groupValues[2]}.",
            primary = FlowAction("track:" + com.meowmba.shared.attach.Attachments.Tracking(pkg.groupValues[1], pkg.groupValues[2]).url, "Track"),
            secondary = FlowAction("later", "Later"),
            urgency = Urgency.USEFUL, certainty = Certainty.CONFIRMED, sources = emptyList(),
        ) else FlowItem(
            intentionId = note.id, kind = guessKind(note.text), headline = note.text,
            because = "You asked Meow to keep this in mind.",
            primary = FlowAction("done", "Done"), secondary = FlowAction("later", "Later"),
            urgency = Urgency.USEFUL, certainty = Certainty.CONFIRMED, sources = emptyList(),
        )
    }
    // A goal connection adds context to a row without changing its action or urgency (Android decorate).
    val stars = northStarsForSnapshot
    fun linked(item: FlowItem) = stars?.goalFor("flow:${item.intentionId}")?.let { item.copy(because = item.because + " → " + it) } ?: item
    // The phone's own facts (next meeting, due reminders) lead when they are more pressing.
    return (context.filter { it.intentionId !in com.meowmba.shared.core.flow.FlowContextState.snoozed(now) } + items)
        .sortedByDescending { it.urgency.ordinal }.map(::linked)
}
