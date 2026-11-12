package com.meowmba.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowmba.shared.computer.SshProfile
import com.meowmba.shared.computer.SshProfiles
import com.meowmba.shared.computer.Terminal

/** Android CompanionTerminalPanel (SSH targets): pick a server, get a real shell. */
@Composable
internal fun TerminalPanel() {
    val status by Terminal.status.collectAsState()
    val output by Terminal.output.collectAsState()
    val profiles by SshProfiles.all.collectAsState()
    val current by Terminal.profile.collectAsState()
    var editing by remember { mutableStateOf<SshProfile?>(null) }
    var line by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(output.length) { scroll.animateScrollTo(scroll.maxValue) }

    if (editing != null) { ServerEditor(editing!!) { editing = null }; return }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == Terminal.Status.IDLE || status == Terminal.Status.CLOSED) {
            Text("SSH servers", style = MaterialTheme.typography.titleMedium)
            if (profiles.isEmpty()) Text("Add a computer you own (Linux, a Mac with Remote Login on, or a Raspberry Pi). " +
                "Passwords and keys stay in this iPhone's Keychain; the server's host key is pinned the first time you connect.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            profiles.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { Terminal.connect(p) }.padding(vertical = 8.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall)
                        Text("${p.user}@${p.host}:${p.port}" + if (p.hostKey.isNotEmpty()) " · host key pinned" else "", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ editing = p }) { Text("Edit") }
                    Button({ Terminal.connect(p) }) { Text("Connect") }
                }
            }
            // One tap for the paired desktop: its address is already known from the bridge pairing.
            val mac = com.meowmba.shared.bridge.DesktopBridge.paired.collectAsState().value
            if (mac != null && profiles.none { it.host == mac.host }) {
                Button({ editing = SshProfile(SshProfiles.newId(), mac.name.ifBlank { "My Mac" }, mac.host, 22, "") }, Modifier.fillMaxWidth()) {
                    Text("Add ${mac.name.ifBlank { "my Mac" }} (${mac.host})")
                }
                Text("Turn on Remote Login on the Mac first: System Settings › General › Sharing. Then enter your Mac user name and password.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton({ editing = SshProfile(SshProfiles.newId(), "My server", "", 22, "") }) { Text("Add a server") }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Text((current?.let { "${it.user}@${it.host}" } ?: "") + if (status == Terminal.Status.CONNECTING) " · connecting…" else "",
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(Terminal::interrupt) { Text("Ctrl-C") }
            TextButton(Terminal::clear) { Text("Clear") }
            TextButton(Terminal::disconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
        }
        Surface(Modifier.fillMaxWidth().weight(1f), color = Color(0xFF0B1418), shape = MaterialTheme.shapes.medium) {
            SelectionContainer(Modifier.verticalScroll(scroll).padding(10.dp)) {
                Text(output.ifEmpty { "No session yet." }, color = Color(0xFFC6EB78), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        if (status == Terminal.Status.CONNECTED) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(line, { line = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("command") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { Terminal.sendLine(line); line = "" }))
            TextButton({ Terminal.sendRaw("\t") }) { Text("Tab") }
            Button({ Terminal.sendLine(line); line = "" }) { Text("Send") }
        }
    }
}

@Composable
private fun ServerEditor(start: SshProfile, onDone: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var host by remember { mutableStateOf(start.host) }
    var port by remember { mutableStateOf(start.port.toString()) }
    var user by remember { mutableStateOf(start.user) }
    var usesKey by remember { mutableStateOf(start.usesKey) }
    var secret by remember { mutableStateOf("") }
    val valid = host.isNotBlank() && !host.any { it.isWhitespace() } && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (start.host.isEmpty()) "Add a server" else "Edit ${start.name}", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Address") }, placeholder = { Text("my-server or 192.168.1.10") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(user, { user = it.trim() }, Modifier.weight(1f), label = { Text("User") }, singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(96.dp), label = { Text("Port") }, singleLine = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!usesKey, { usesKey = false }, { Text("Password") })
            FilterChip(usesKey, { usesKey = true }, { Text("Private key") })
        }
        if (usesKey) OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("OpenSSH private key (ed25519 or RSA)") }, minLines = 4,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            supportingText = { Text(if (start.host.isNotEmpty()) "Leave empty to keep the saved key." else "Unencrypted keys only.") })
        else OutlinedTextField(secret, { secret = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { if (start.host.isNotEmpty()) Text("Leave empty to keep the saved password.") })
        if (start.hostKey.isNotEmpty()) TextButton({ SshProfiles.save(start.copy(hostKey = ""), null); onDone() }) { Text("Forget the pinned host key (only if the server was reinstalled)") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = valid, onClick = {
                val changedTarget = host != start.host || (port.toIntOrNull() ?: 22) != start.port
                SshProfiles.save(start.copy(name = name.ifBlank { host }, host = host, port = port.toInt(), user = user, usesKey = usesKey,
                    hostKey = if (changedTarget) "" else start.hostKey), secret.ifEmpty { null })
                onDone()
            }) { Text("Save") }
            TextButton(onDone) { Text("Cancel") }
            if (start.host.isNotEmpty()) TextButton({ SshProfiles.delete(start.id); onDone() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
    }
}
