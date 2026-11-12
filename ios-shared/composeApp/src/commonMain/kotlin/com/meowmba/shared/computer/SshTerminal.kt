package com.meowmba.shared.computer

import com.meowmba.shared.bridge.DesktopBridge
import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * iOS: an SSH client with a PTY shell (Citadel / SwiftNIO SSH). Events: "hostkey" (fingerprint
 * seen on first connect), "connected", "output", "closed", "error". All callbacks on main.
 */
interface SshDriver {
    fun open(id: String, host: String, port: Int, user: String, password: String, privateKey: String,
             expectedFingerprint: String, onEvent: (kind: String, payload: String) -> Unit)
    fun write(id: String, text: String)
    fun resize(id: String, cols: Int, rows: Int)
    fun close(id: String)
}

/** Android ConnectionProfile (SSH), minus secrets: passwords and keys live in the Keychain. */
@Serializable
data class SshProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val usesKey: Boolean = false,
    /** SHA-256 of the server's host key, pinned on first connect (Android pinHostKeyOnFirstConnect). */
    val hostKey: String = "",
)

object SshProfiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val all = MutableStateFlow(runCatching { json.decodeFromString<List<SshProfile>>(DefaultTextStorage.readText(KEY) ?: "[]") }.getOrDefault(emptyList()))

    fun save(profile: SshProfile, secret: String?) {
        all.value = listOf(profile) + all.value.filter { it.id != profile.id }
        DefaultTextStorage.writeTextAtomic(KEY, json.encodeToString(all.value))
        if (!secret.isNullOrEmpty()) DesktopBridge.secrets?.writeSecret("ssh_${profile.id}", secret)
    }
    fun delete(id: String) {
        all.value = all.value.filter { it.id != id }
        DefaultTextStorage.writeTextAtomic(KEY, json.encodeToString(all.value))
        DesktopBridge.secrets?.deleteSecret("ssh_$id")
    }
    fun secret(id: String) = DesktopBridge.secrets?.readSecret("ssh_$id").orEmpty()
    fun newId() = PlatformId.shortId()
    private const val KEY = "ssh_profiles.json"
}

/** Android CompanionTerminalSession for SSH: one PTY shell, text output, line input, Ctrl-C. */
object Terminal {
    var driver: SshDriver? = null
    enum class Status { IDLE, CONNECTING, CONNECTED, CLOSED }
    val status = MutableStateFlow(Status.IDLE)
    val output = MutableStateFlow("")
    val profile = MutableStateFlow<SshProfile?>(null)
    private var sessionId: String? = null
    private const val MAX = 60_000

    fun connect(p: SshProfile) {
        val d = driver ?: return append("SSH isn't available in this build.\n")
        disconnect()
        val id = PlatformId.shortId(); sessionId = id
        profile.value = p; status.value = Status.CONNECTING
        append("Connecting to ${p.user}@${p.host}:${p.port}…\n")
        val secret = SshProfiles.secret(p.id)
        d.open(id, p.host, p.port, p.user, if (p.usesKey) "" else secret, if (p.usesKey) secret else "", p.hostKey) { kind, payload ->
            if (sessionId != id) return@open
            when (kind) {
                "hostkey" -> {
                    SshProfiles.save(p.copy(hostKey = payload), null)
                    profile.value = p.copy(hostKey = payload)
                    append("[host key pinned: SHA256 ${payload.take(16)}…]\n")
                }
                "connected" -> { status.value = Status.CONNECTED; append("[connected]\n") }
                "output" -> append(Ansi.strip(payload))
                "closed" -> { status.value = Status.CLOSED; append("\n[${payload.ifBlank { "session ended" }}]\n") }
                "error" -> { status.value = Status.CLOSED; append("\n[${payload}]\n") }
            }
        }
    }

    fun sendLine(line: String) { if (status.value == Status.CONNECTED) sessionId?.let { driver?.write(it, line + "\n") } }
    fun sendRaw(text: String) { if (status.value == Status.CONNECTED) sessionId?.let { driver?.write(it, text) } }
    fun interrupt() = sendRaw("\u0003")
    fun resize(cols: Int, rows: Int) { sessionId?.let { driver?.resize(it, cols.coerceIn(20, 500), rows.coerceIn(8, 200)) } }
    fun disconnect() { sessionId?.let { driver?.close(it) }; sessionId = null; if (status.value != Status.IDLE) status.value = Status.CLOSED }
    fun clear() { output.value = "" }

    private fun append(text: String) {
        val next = output.value + text
        output.value = if (next.length > MAX) next.takeLast(MAX) else next
    }
}

/** Terminal output is shown as text: colour/cursor escapes are removed, carriage returns folded. */
object Ansi {
    private val csi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val other = Regex("\u001B[()][A-Za-z0-9]|\u001B[=>78cDEHMNOZ]")
    fun strip(raw: String): String = raw.replace(osc, "").replace(csi, "").replace(other, "")
        .replace("\r\n", "\n").replace(Regex("[^\n]*\r(?!\n)"), "").replace("\u0007", "")
        .let { s -> buildString { s.forEach { c -> if (c == '\b') { if (isNotEmpty()) deleteAt(length - 1) } else append(c) } } }
}
