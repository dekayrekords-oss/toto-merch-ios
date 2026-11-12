package com.meowmba.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.bridge.DesktopBridge
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

internal enum class ComputerTab(val label: String) { DESKTOP("Desktop"), FILES("Files"), SCREEN("Screen"), TERMINAL("Terminal") }

/**
 * Android Computer / "Your computer" for iOS: the paired Meowmba desktop bridge. Every action is a
 * tap by you; results say what the desktop reported, never more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComputerScreen(initial: ComputerTab = ComputerTab.DESKTOP, onBack: () -> Unit, terminal: @Composable () -> Unit = { TerminalPanel() }) {
    val paired by DesktopBridge.paired.collectAsState()
    var tab by remember { mutableStateOf(initial) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Computer", style = MaterialTheme.typography.headlineSmall)
                    Text(paired?.let { "Paired with ${it.name.ifBlank { it.host }}" } ?: "Not paired", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                ComputerTab.entries.forEach { t -> Tab(tab == t, { tab = t }, text = { Text(t.label) }) }
            }
            Spacer(Modifier.height(12.dp))
            when {
                tab == ComputerTab.TERMINAL -> terminal()
                paired == null -> PairDesktop()
                tab == ComputerTab.DESKTOP -> DesktopTab()
                tab == ComputerTab.FILES -> FilesTab()
                tab == ComputerTab.SCREEN -> ScreenTab()
            }
        }
    }
}

@Composable
private fun PairDesktop() {
    val scope = rememberCoroutineScope()
    var payload by remember { mutableStateOf(DesktopBridge.pendingPayload.value.orEmpty()) }
    LaunchedEffect(Unit) { DesktopBridge.pendingPayload.value = null }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Pair your computer", style = MaterialTheme.typography.titleLarge)
        Text("On the computer, run the Meowmba desktop bridge with --pair:", style = MaterialTheme.typography.bodyMedium)
        SelectionContainer { Text("python3 meowmba-bridge.py --pair", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
        Text("It prints a pairing payload. Copy it on the Mac — Universal Clipboard brings it to this iPhone — and paste it here. " +
            "The desktop's certificate is pinned from the payload; every later request is signed, and the secret stays in this iPhone's Keychain.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(payload, { payload = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Pairing payload") }, minLines = 3, enabled = !busy)
        OutlinedButton(enabled = !busy, onClick = {
            val text = com.meowmba.shared.platform.PlatformDrivers.clipboard?.readText()?.trim()
            if (text.isNullOrBlank()) result = "The clipboard is empty. Copy the payload on the Mac first." else payload = text
        }) { Text("Paste from clipboard") }
        Button(enabled = payload.isNotBlank() && !busy, onClick = {
            busy = true; result = null
            scope.launch {
                result = DesktopBridge.pair(payload, "iPhone")
                    .fold({ "Paired with $it." }, { it.message ?: "Pairing failed." })
                busy = false
            }
        }) { Text(if (busy) "Pairing…" else "Pair") }
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("One phone can be paired with a desktop at a time; pairing this iPhone replaces the previous phone on that computer.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DesktopTab() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var app by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf("") }
    var build by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    fun run(label: String, block: suspend () -> Result<String>) {
        result = "$label…"
        scope.launch {
            val r = block()
            result = r.getOrElse { "$label failed: ${it.message}" }
            // Every desktop action leaves a receipt in Meow's work.
            computerWork?.let { w -> val id = w.start("Desktop: $label", "desktop")
                w.finish(id, r.isSuccess, com.meowmba.shared.work.WorkOutcome.UNCONFIRMED, result.orEmpty()) }
        }
    }
    LaunchedEffect(Unit) {
        status = DesktopBridge.status().fold({ s ->
            "${s.name} · ${s.platform} · bridge ${s.version} · up ${s.uptimeSeconds / 3600} h" +
                (if (s.workspaceRoots.isNotEmpty()) "\nWorkspace: " + s.workspaceRoots.joinToString(", ") else "\nNo workspace folders declared in ~/.meowmba-bridge/config.json")
        }, { "Can't reach the desktop: ${it.message}" })
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(status ?: "Checking the desktop…", style = MaterialTheme.typography.bodyMedium)
        result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("Open on the computer", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(app, { app = it }, Modifier.weight(1f), label = { Text("App") }, singleLine = true)
            TextButton(enabled = app.isNotBlank(), onClick = { run("Open ${app.trim()}") { DesktopBridge.openApp(app.trim()) } }) { Text("Open") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), label = { Text("Web address") }, singleLine = true)
            TextButton(enabled = url.startsWith("http"), onClick = { run("Open link") { DesktopBridge.openUrl(url.trim()) } }) { Text("Open") }
        }
        Text("Media", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("play" to "Play", "pause" to "Pause", "next" to "Next").forEach { (a, l) ->
                OutlinedButton({ run(l) { DesktopBridge.media(a) } }) { Text(l) }
            }
        }
        Text("Clipboard", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(clip, { clip = it }, Modifier.fillMaxWidth(), label = { Text("Text") }, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ scope.launch { DesktopBridge.clipboardGet().fold({ clip = it; result = "Copied the computer's clipboard here." }, { result = it.message }) } }) { Text("Get") }
            OutlinedButton(enabled = clip.isNotEmpty(), onClick = { run("Set clipboard") { DesktopBridge.clipboardSet(clip) } }) { Text("Send to computer") }
        }
        Text("Builds", style = MaterialTheme.typography.titleMedium)
        Text("Only commands pre-declared in the computer's config.json run — never a shell.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(build, { build = it }, Modifier.weight(1f), label = { Text("Command name") }, singleLine = true)
            TextButton(enabled = build.isNotBlank(), onClick = {
                output = null
                run("Build ${build.trim()}") { DesktopBridge.runBuild(build.trim()).map { r ->
                    output = r.output
                    "${r.name}: exit ${r.exitCode}${if (r.timedOut) " (timed out)" else ""} in ${r.durationSeconds.toInt()} s"
                } }
            }) { Text("Run") }
        }
        output?.let { SelectionContainer { Text(it.takeLast(8000), fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
        HorizontalDivider()
        TextButton({ DesktopBridge.unpair() }) { Text("Unpair this computer", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FilesTab() {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<DesktopBridge.DirListing?>(null) }
    var file by remember { mutableStateOf<DesktopBridge.FileContent?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        error = null
        DesktopBridge.listFiles(path).fold({ listing = it }, { error = it.message })
    }
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val open = file
        if (open != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ file = null; editing = null }) { Text("‹ Back") }
                Text(open.path, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                if (editing == null && !open.truncated) TextButton({ editing = open.content }) { Text("Edit") }
                if (editing != null) TextButton({ confirmSave = true }) { Text("Save") }
            }
            if (open.truncated) Text("Showing the first part of a large file; editing is off.", style = MaterialTheme.typography.bodySmall)
            if (editing != null) OutlinedTextField(editing!!, { editing = it }, Modifier.fillMaxSize(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
            else SelectionContainer(Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text(open.content, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (path.isNotEmpty()) TextButton({ path = path.substringBeforeLast('/', "") }) { Text("‹ Up") }
                Text(listing?.path ?: path.ifEmpty { "Workspace" }, style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listing?.entries?.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val child = listOf(listing?.path ?: path, e.name).filter { it.isNotEmpty() }.joinToString("/")
                        if (e.dir) path = child
                        else scope.launch { DesktopBridge.readFile(child).fold({ file = it }, { error = it.message }) }
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (e.dir) Icons.Default.Folder else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(e.name, Modifier.weight(1f))
                        if (!e.dir) Text("${e.size / 1024} KB", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (listing?.truncated == true) Text("More files not shown.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val f = file
    if (confirmSave && f != null) AlertDialog(onDismissRequest = { confirmSave = false },
        title = { Text("Save to the computer?") },
        text = { Text("This replaces ${f.path} on the computer. The desktop keeps the previous version as a .bak file.") },
        confirmButton = { TextButton({
            confirmSave = false
            val content = editing ?: return@TextButton
            scope.launch { DesktopBridge.writeFile(f.path, content).fold({ error = it; file = f.copy(content = content); editing = null }, { error = it.message }) }
        }) { Text("Save") } },
        dismissButton = { TextButton({ confirmSave = false }) { Text("Cancel") } })
}

@Composable
private fun ScreenTab() {
    val scope = rememberCoroutineScope()
    var png by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val image = remember(png) { png?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("A single picture of the computer's screen, taken when you tap. Nothing is recorded.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch { DesktopBridge.screenshot().fold({ png = it }, { error = it.message }); busy = false }
        }) { Text(if (busy) "Capturing…" else "Capture screen") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        image?.let { Image(it, "Computer screen", Modifier.fillMaxWidth()) }
    }
}

/** Desktop receipts land in the same journal as phone actions (set by the view model). */
internal var computerWork: com.meowmba.shared.work.WorkJournal? = null
