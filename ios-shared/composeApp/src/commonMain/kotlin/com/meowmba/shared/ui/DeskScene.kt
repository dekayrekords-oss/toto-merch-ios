package com.meowmba.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.core.flow.FlowItem
import com.meowmba.shared.core.flow.FlowKind
import com.meowmba.shared.core.flow.FlowSnapshot
import com.meowmba.shared.resources.Res
import com.meowmba.shared.ui.desk.*
import kotlinx.coroutines.delay

/**
 * Android DeskRoute + DeskWorkspace (build 183): Meow's apartment — living room, office,
 * bedroom — bound to the same Flow items. Panels open on tap; the cat walks over and acts.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DeskScene(
    notes: List<FlowNote>,
    onKeep: (String) -> Unit,
    onDone: (String) -> Unit,
    onLater: (String) -> Unit,
    onMenu: () -> Unit,
    onFlow: () -> Unit,
    onAvatar: () -> Unit,
    onChat: () -> Unit,
    onSettings: () -> Unit,
    micOn: Boolean = true,
    onMicToggle: () -> Unit = {},
    /** The AI's turn: the cat types while Meow thinks and says the reply in its bubble. */
    thinking: Boolean = false,
    replying: Boolean = false,
    reply: String? = null,
    question: String? = null,
    replyDoneAt: Long = 0L,
    onBubbleTap: () -> Unit = {},
    listening: Boolean = false,
    weatherLine: String? = null,
    night: Boolean? = null,
    northStars: com.meowmba.shared.core.northstar.NorthStars? = null,
    onWork: (() -> Unit)? = null,
    onWeather: () -> Unit = {},
    onNotes: () -> Unit = {},
    onApps: () -> Unit = {},
    onAsk: ((String) -> Unit)? = null,
    onOpenUrl: (String, (Boolean) -> Unit) -> Unit = { _, done -> done(false) },
) {
    DeskPainter.hand = FontFamily.Serif
    var now by remember { mutableStateOf(PlatformTime.currentMillis()) }
    LaunchedEffect(Unit) { while (true) { now = PlatformTime.currentMillis(); delay(30_000) } }
    val offset = PlatformTime.utcOffsetMinutes()
    val minuteOfDay = (((now / 60_000L + offset) % 1440L + 1440L) % 1440L).toInt()
    val context by com.meowmba.shared.core.flow.FlowContextState.items.collectAsState()
    val snapshot: FlowSnapshot = flowSnapshotFor(notes, now, emptyList(), context)
    // Everything open, not just the four Flow shows: the tray, jar and "N open" count them all.
    val items = allFlowItems(notes, now, context)
    val open = notes.filter { !it.done && it.snoozedUntil <= now }
    val starViews = northStars?.views?.collectAsState()?.value.orEmpty()
    val mainStar = starViews.firstOrNull()

    // The stereo shows Apple Music while the apartment is on screen (Android RoomMediaWatcher).
    var music by remember { mutableStateOf<RoomMedia?>(null) }
    var musicAccess by remember { mutableStateOf(com.meowmba.shared.platform.PlatformDrivers.music?.hasAccess() == true) }
    LaunchedEffect(musicAccess) {
        val driver = com.meowmba.shared.platform.PlatformDrivers.music ?: return@LaunchedEffect
        while (true) {
            music = driver.nowPlaying()?.split('|')?.let { p -> RoomMedia(p[0], p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2) == "1", "Music") }
            delay(5_000)
        }
    }
    val sticky = items.firstOrNull()?.let { listOf("Next up") + wrapWords(it.headline, 2) } ?: listOf("All clear")
    val facts = DeskFacts(
        clockHour = minuteOfDay / 60, clockMinute = minuteOfDay % 60,
        night = night ?: (minuteOfDay >= 19 * 60 || minuteOfDay < 6 * 60),
        weatherLine = weatherLine,
        projectTitle = mainStar?.star?.title.orEmpty(),
        projectStatus = mainStar?.nextStep?.let { "Next: $it" }.orEmpty(),
        pinnedNote = listOf("Meow", com.meowmba.shared.core.DeviceInfo.appVersion().ifBlank { "iOS" }),
        nextEventTime = sticky.first(), nextEventWords = sticky.drop(1),
        monitorLines = listOfNotNull(
            items.firstOrNull { it.kind == FlowKind.WORK }?.let { MonitorLine("> " + it.headline.take(16), true) },
            MonitorLine("${items.size} open"),
            MonitorLine("${items.count { it.kind == FlowKind.PEOPLE }} waiting"),
        ).take(3),
        ideaQueued = open.isNotEmpty(),
    )
    val room = RoomState(music = music, mediaAccess = true)
    val desk = DeskState.from(snapshot, facts, items.map { it.kind to it.headline }, room)

    // Only a real reply is shown in the bubble (Android DeskCatReplies): live text, then the final turn.
    val activity = when {
        listening -> DeskCatActivity.LISTENING
        thinking -> DeskCatActivity.THINKING
        replying -> DeskCatActivity.SPEAKING
        else -> DeskCatActivity.IDLE
    }
    val firstDone = remember { replyDoneAt }
    val catReply = when {
        replying && !reply.isNullOrBlank() -> DeskCatReply("live:$question", reply)
        replyDoneAt != 0L && replyDoneAt != firstDone && !reply.isNullOrBlank() -> DeskCatReply("done:$replyDoneAt", reply)
        else -> null
    }

    var selected by remember { mutableStateOf<DeskObject?>(null) }
    var asking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var starsOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        DeskHabitat(desk, Modifier.fillMaxSize(),
            topInset = if (maxWidth > maxHeight) 0.dp else 52.dp, bottomInset = if (maxWidth > maxHeight) 26.dp else 66.dp,
            activity = activity, reply = catReply, wanderEveryMs = if (DemoHooks.stillCat.collectAsState().value) 0 else 14_000,
            lineFor = { deskLine(it, desk) },
            onTap = {
                when {
                    it == DeskObject.CLOCK -> onOpenUrl("clock-alarm://") { ok -> if (!ok) selected = it }
                    it == DeskObject.PROJECT && northStars != null -> starsOpen = true
                    it == DeskObject.ARCADE -> onOpenUrl("delta://") { ok -> if (!ok) selected = it }
                    it == DeskObject.DRAWER_TOP -> onApps()
                    !it.catOnly -> selected = it
                }
            })
        if (onAsk != null) FilledTonalButton(onClick = { asking = true },
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) { Text("Ask Meow") }

        if (adding) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(bottom = 68.dp, start = 16.dp, end = 16.dp)
            .background(Color(0xFF193039), RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text("Add to Flow", color = SHEET_TEXT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("What should Meow keep in mind?") })
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Keep", Modifier.clickable { onKeep(draft); draft = ""; adding = false }, color = Color(0xFFC6EB78), fontWeight = FontWeight.Bold)
                Text("Cancel", Modifier.clickable { adding = false }, color = SHEET_TEXT)
            }
        }
        // Sideways the bar is 60% slimmer, leaving the room as much height as possible.
        val sideways = maxWidth > maxHeight
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (sideways) 26.dp else 66.dp)
                .background(Color(0xFF12232A)).padding(horizontal = 28.dp, vertical = if (sideways) 2.dp else 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            listOf("Menu" to onMenu, "Flow" to onFlow, (if (micOn) "Mic on" else "Mic off") to onMicToggle,
                "Add" to { adding = true }, "Settings" to onSettings).forEach { (label, action) ->
                Text(label, Modifier.clickable(onClick = action), color = SHEET_TEXT, fontSize = if (sideways) 11.sp else 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (asking && onAsk != null) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { asking = false }, title = { Text("Ask Meow") },
            text = { OutlinedTextField(q, { q = it.take(4_000) }, label = { Text("What’s on your mind?") }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = q.isNotBlank() && !thinking, onClick = { val t = q.trim(); asking = false; onAsk(t) }) { Text("Ask") } },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } })
    }
    if (starsOpen && northStars != null) ModalBottomSheet(onDismissRequest = { starsOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        com.meowmba.shared.ui.flow.NorthStarSheet(northStars, onClose = { starsOpen = false }, onOpenSource = { key ->
            starsOpen = false; if (key.startsWith("task:")) onWork?.invoke()
        })
    }
    val obj = selected
    if (obj != null) ModalBottomSheet(onDismissRequest = { selected = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF193039), contentColor = SHEET_TEXT,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(deskTitle(obj), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { selected = null }) { Text("Close", color = SHEET_TEXT) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF33505A))
            fun close() { selected = null }
            when (obj) {
                DeskObject.DRAWER_BOTTOM -> {
                    Text("Meow's tools live in the menu; your iPhone apps are in the top drawer.", color = SHEET_MUTE)
                    DeskButton("Open the menu") { close(); onMenu() }
                    DeskButton("Open apps", outlined = true) { close(); onApps() }
                }
                DeskObject.BUILD -> {
                    Text("Meow's saved work: reminders, drafts, screen help and shortcuts, with what actually happened.", color = SHEET_MUTE)
                    if (onWork != null) DeskButton("Meow’s work") { close(); onWork() }
                    DeskButton("App settings", outlined = true) { close(); onSettings() }
                }
                DeskObject.STEREO -> MusicPanel(music, musicAccess, onAllow = {
                    com.meowmba.shared.platform.PlatformDrivers.music?.requestMusicAccess { musicAccess = it }
                }, onOpen = { onOpenUrl("music://") {} })
                DeskObject.BIG_TV -> {
                    Text("iOS keeps what other apps are playing private, so the TV can't show YouTube's video here.", color = SHEET_MUTE)
                    DeskButton("Open YouTube") { onOpenUrl("youtube://") { ok -> if (!ok) onOpenUrl("https://m.youtube.com") {} } }
                }
                DeskObject.BOOKSHELF -> {
                    Text("Audiobooks play in Books or Audible; iOS doesn't share their titles with other apps.", color = SHEET_MUTE)
                    DeskButton("Open Books") { onOpenUrl("ibooks://") {} }
                    DeskButton("Open Audible", outlined = true) { onOpenUrl("audible://") { ok -> if (!ok) onOpenUrl("https://www.audible.com") {} } }
                    ManualSection()
                }
                DeskObject.ARCADE -> {
                    Text("The old console opens an emulator. On iPhone that's Delta, free on the App Store; you'll need your own game files.", color = SHEET_MUTE)
                    DeskButton("View Delta on the App Store") { close(); onOpenUrl("https://apps.apple.com/app/id1048524688") {} }
                }
                DeskObject.PICTURE_LIVING_A, DeskObject.PICTURE_LIVING_B, DeskObject.PICTURE_BED_A, DeskObject.PICTURE_BED_B -> {
                    val all by com.meowmba.shared.ui.desk.DeskPictures.choices.collectAsState()
                    val current = com.meowmba.shared.ui.desk.DeskPictures.choice(obj, all)
                    Text("Hang one of Meow's drawings, or a photo of your own.", color = SHEET_MUTE)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.meowmba.shared.ui.desk.DeskPictures.ART_NAMES.forEachIndexed { i, name ->
                            androidx.compose.material3.FilterChip(selected = current == "art:$i",
                                onClick = { com.meowmba.shared.ui.desk.DeskPictures.set(obj, "art:$i") }, label = { Text(name) })
                        }
                    }
                    val picker = com.meowmba.shared.platform.PlatformDrivers.photos
                    if (picker != null) DeskButton(if (current.startsWith("art:")) "Choose a photo" else "Choose another photo") {
                        picker.pickImage { path -> if (path != null) com.meowmba.shared.ui.desk.DeskPictures.set(obj, path) }
                    }
                }
                DeskObject.CLOCK -> {
                    Text("${desk.clockHour}:${desk.clockMinute.toString().padStart(2, '0')}", color = SHEET_MUTE)
                    DeskButton("Open Clock") { close(); onOpenUrl("clock-alarm://") {} }
                }
                else -> {
                    val o = if (obj == DeskObject.BIG_WINDOW) DeskObject.WINDOW else obj
                    val relevant: List<FlowItem> = when (o) {
                        DeskObject.MONEY -> items.filter { it.kind == FlowKind.MONEY }
                        DeskObject.INBOX -> items.filter { it.kind == FlowKind.PEOPLE }
                        DeskObject.IDEA -> items.filter { it.kind == FlowKind.OTHER || it.kind == FlowKind.ERRAND || it.kind == FlowKind.HOME }
                        DeskObject.TODAY -> items
                        else -> emptyList()
                    }
                    Text(when (o) {
                        DeskObject.WINDOW -> desk.weatherLine ?: "Weather isn't set up yet. Open Weather to choose a location."
                        DeskObject.TODAY -> if (items.isEmpty()) "Nothing waiting. Tell Meow what's on your mind in Flow." else "Everything Meow is keeping in mind, most pressing first."
                        DeskObject.INBOX -> if (relevant.isEmpty()) "Nobody is waiting on you." else "People waiting on you."
                        DeskObject.MONEY -> if (relevant.isEmpty()) "Nothing due." else "Money things coming up."
                        DeskObject.IDEA -> if (relevant.isEmpty()) "No ideas or errands waiting." else "Things you asked Meow to keep in mind."
                        else -> ""
                    }, style = MaterialTheme.typography.bodyLarge, color = SHEET_MUTE)
                    relevant.take(12).forEach { item ->
                        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            Text(item.headline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (item.because.isNotBlank()) Text(item.because, style = MaterialTheme.typography.bodyMedium, color = SHEET_MUTE)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onDone(item.intentionId) }) { Text(item.primary.label, color = Color(0xFFC6EB78)) }
                                item.secondary?.let { TextButton(onClick = { onLater(item.intentionId) }) { Text("Later", color = SHEET_TEXT) } }
                            }
                            HorizontalDivider(color = Color(0xFF33505A))
                        }
                    }
                    val action = when (o) {
                        DeskObject.WINDOW -> "Open Weather" to onWeather
                        DeskObject.IDEA -> "Open Notes" to onNotes
                        else -> "Open Flow" to onFlow
                    }
                    DeskButton(action.first, outlined = true) { close(); action.second() }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private val SHEET_TEXT = Color(0xFFF4EEDB)
private val SHEET_MUTE = Color(0xFFBED0CD)

@Composable
private fun DeskButton(label: String, outlined: Boolean = false, onClick: () -> Unit) {
    val m = Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 48.dp)
    if (outlined) OutlinedButton(onClick = onClick, border = BorderStroke(2.dp, Color(0xFF819F9F)), modifier = m) { Text(label, color = SHEET_TEXT) }
    else Button(onClick = onClick, modifier = m) { Text(label) }
}

@Composable
private fun MusicPanel(now: RoomMedia?, access: Boolean, onAllow: () -> Unit, onOpen: () -> Unit) {
    val driver = com.meowmba.shared.platform.PlatformDrivers.music
    if (!access) {
        Text("To show the song on the stereo, Meow needs Media & Apple Music access. It reads the title only while this room is on screen, and never listens through the microphone.",
            style = MaterialTheme.typography.bodyLarge, color = SHEET_MUTE)
        DeskButton("Allow media info", onClick = onAllow)
    } else if (now == null) Text("Nothing is playing in Music. Other apps (Spotify, YouTube) keep their songs private on iOS.",
        style = MaterialTheme.typography.bodyLarge, color = SHEET_MUTE)
    else {
        Text(if (now.playing) "Playing" else "Paused", style = MaterialTheme.typography.labelLarge, color = Color(0xFFC6EB78))
        Text(now.title, style = MaterialTheme.typography.titleLarge)
        now.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = SHEET_MUTE) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { driver?.togglePlay() }) { Text(if (now.playing) "Pause" else "Play") }
            OutlinedButton(onClick = { driver?.next() }, border = BorderStroke(2.dp, Color(0xFF819F9F))) { Text("Next", color = SHEET_TEXT) }
        }
    }
    DeskButton("Open Music", outlined = true, onClick = onOpen)
}

@Composable
private fun ManualSection() {
    var reading by remember { mutableStateOf(false) }
    var md by remember { mutableStateOf("") }
    LaunchedEffect(reading) { if (reading && md.isEmpty()) md = runCatching { Res.readBytes("files/meow_manual.md").decodeToString() }.getOrDefault("") }
    DeskButton(if (reading) "Close the manual" else "Read the Meow manual", outlined = true) { reading = !reading }
    if (reading) Column(Modifier.padding(top = 12.dp)) {
        md.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                else -> Text(bold(if (line.startsWith("- ")) "•  " + line.removePrefix("- ") else line), style = MaterialTheme.typography.bodyMedium, color = SHEET_MUTE)
            }
        }
    }
}

private fun bold(text: String) = androidx.compose.ui.text.buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) { pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = SHEET_TEXT)); append(part); pop() } else append(part)
    }
}

private fun wrapWords(text: String, lines: Int, width: Int = 9): List<String> {
    val out = ArrayList<String>(); var cur = ""
    for (w in text.split(' ')) {
        if (cur.isEmpty()) cur = w else if (cur.length + 1 + w.length <= width) cur += " $w" else { out += cur; cur = w }
        if (out.size == lines) break
    }
    if (out.size < lines && cur.isNotEmpty()) out += cur
    return out.take(lines).map { it.take(width + 2) }
}

/** What the cat says when it reaches an object: real content, one line (Android deskLine). */
internal fun deskLine(obj: DeskObject, s: DeskState): String = when (obj) {
    DeskObject.INBOX -> if (s.trayCount == 0) "Nobody's waiting on you." else "${s.trayCount} ${if (s.trayCount == 1) "person is" else "people are"} waiting on you."
    DeskObject.MONEY -> s.jarNote?.let { "$it is coming up." } ?: "Nothing due."
    DeskObject.TODAY -> if (s.sticky.firstOrNull() == "All clear") "Nothing next. Nice." else "Next: ${s.sticky.drop(1).joinToString(" ")}"
    DeskObject.PROJECT -> if (s.projectTitle.isBlank()) "No North Star pinned yet." else "Back to ${s.projectTitle}?"
    DeskObject.BUILD -> s.monitorLines.firstOrNull()?.text?.removePrefix("> ") ?: "All quiet."
    DeskObject.IDEA -> if (s.lampOn) "I'm keeping a few things in mind for you." else "No ideas waiting."
    DeskObject.WINDOW -> s.weatherLine ?: "Weather isn't set up yet."
    DeskObject.CLOCK -> "Opening your clock."
    DeskObject.DRAWER_TOP, DeskObject.DRAWER_BOTTOM -> "Here's what's in the drawer."
    DeskObject.BIG_WINDOW -> s.weatherLine?.let { "$it. Nice view." } ?: "Nice view."
    DeskObject.ARCADE -> "Player one, ready."
    DeskObject.TOWER_BED -> "Best seat in the house."
    DeskObject.BED -> "Nap time."
    DeskObject.FOOD -> "Snack break!"
    DeskObject.LITTER -> "A little privacy, please."
    DeskObject.BOOKSHELF -> s.room.audiobook?.let { "Listening to ${it.title}." } ?: "Audiobooks, and the Meow manual."
    DeskObject.BIG_TV -> "Want to watch something?"
    DeskObject.STEREO -> s.room.music?.let { "♪ ${it.title}${it.subtitle?.let { a -> " · $a" } ?: ""}" } ?: "The stereo's quiet."
    DeskObject.COUCH -> "Couch time."
    DeskObject.TOWER_LIVING -> "Up I go!"
    DeskObject.PICTURE_LIVING_A, DeskObject.PICTURE_LIVING_B, DeskObject.PICTURE_BED_A, DeskObject.PICTURE_BED_B -> "Pretty. Want to hang something else?"
}

internal fun deskTitle(obj: DeskObject) = when (obj) {
    DeskObject.WINDOW, DeskObject.BIG_WINDOW -> "Weather"
    DeskObject.PROJECT -> "North Star"
    DeskObject.TODAY -> "Next up"
    DeskObject.MONEY -> "Money"
    DeskObject.INBOX -> "People waiting"
    DeskObject.IDEA -> "Ideas & errands"
    DeskObject.CLOCK -> "Clock"
    DeskObject.DRAWER_TOP -> "Top drawer"
    DeskObject.DRAWER_BOTTOM -> "Bottom drawer"
    DeskObject.BUILD -> "Computer"
    DeskObject.ARCADE -> "Arcade"
    DeskObject.BOOKSHELF -> "Bookshelf"
    DeskObject.BIG_TV -> "TV"
    DeskObject.STEREO -> "Stereo"
    DeskObject.PICTURE_LIVING_A, DeskObject.PICTURE_LIVING_B, DeskObject.PICTURE_BED_A, DeskObject.PICTURE_BED_B -> "Picture"
    else -> obj.label
}
