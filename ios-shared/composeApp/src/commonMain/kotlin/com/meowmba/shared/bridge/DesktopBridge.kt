package com.meowmba.shared.bridge

import com.meowmba.shared.core.DefaultTextStorage
import com.meowmba.shared.core.PlatformTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import kotlin.coroutines.resume

/** iOS: URLSession POST over TLS that accepts only the certificate with [certSha256] (DER, hex). */
interface PinnedHttpDriver {
    fun post(host: String, port: Int, path: String, body: ByteArray, certSha256: String,
             headers: Map<String, String>, timeoutMs: Int, onResult: (status: Int, body: ByteArray?, error: String?) -> Unit)
    fun randomBytes(count: Int): ByteArray
}

/** iOS: the Keychain (this device only). */
interface SecretStoreDriver {
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String)
    fun deleteSecret(key: String)
}

/**
 * Android DesktopBridgeManager on iOS: pairing with the Meowmba desktop daemon
 * (`bridge/meowmba-bridge.py`), then HMAC-signed RPCs over certificate-pinned TLS.
 * Every call returns a readable failure; nothing is faked.
 */
object DesktopBridge {
    var http: PinnedHttpDriver? = null
    var secrets: SecretStoreDriver? = null

    data class Paired(val host: String, val port: Int, val name: String, val certSha256: String)
    data class Status(val name: String, val version: String, val platform: String, val uptimeSeconds: Long, val workspaceRoots: List<String>, val pairedPhone: String)
    data class FileEntry(val name: String, val dir: Boolean, val size: Long)
    data class DirListing(val path: String, val entries: List<FileEntry>, val truncated: Boolean)
    data class FileContent(val path: String, val size: Long, val content: String, val truncated: Boolean)
    data class RunResult(val name: String, val exitCode: Int, val durationSeconds: Double, val timedOut: Boolean, val output: String)

    val paired = MutableStateFlow(load())
    /** A payload handed over by a meowmba://pair link; the person still taps Pair. */
    val pendingPayload = MutableStateFlow<String?>(null)

    private fun load(): Paired? {
        val raw = DefaultTextStorage.readText("bridge_pairing") ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        if (secrets?.readSecret(SECRET) == null && secrets != null) return null
        return Paired(o["host"]?.jsonPrimitive?.content ?: return null, o["port"]?.jsonPrimitive?.int ?: return null,
            o["name"]?.jsonPrimitive?.content.orEmpty(), o["cert"]?.jsonPrimitive?.content ?: return null)
    }

    /** Called once the drivers are registered (the secret lives in the Keychain). */
    fun refresh() { paired.value = load() }

    fun unpair() {
        DefaultTextStorage.delete("bridge_pairing"); secrets?.deleteSecret(SECRET); paired.value = null
    }

    /** Pair using the payload `meowmba-bridge.py --pair` prints (copy it on the Mac, paste on iPhone). */
    suspend fun pair(payloadBlob: String, phoneName: String): Result<String> {
        val payload = DesktopBridgeProtocol.decodePairingPayload(payloadBlob).getOrElse { return Result.failure(it) }
        return pairWith(payload.host, payload.port, payload.name, payload.certSha256, payload.pairToken, phoneName)
    }

    suspend fun pairWith(host: String, port: Int, name: String, certSha256: String, token: String, phoneName: String): Result<String> {
        val driver = http ?: return Result.failure(Exception("Desktop networking isn't available in this build"))
        val store = secrets ?: return Result.failure(Exception("Secure storage isn't available"))
        val secret = driver.randomBytes(32)
        val secretB64 = DesktopBridgeProtocol.b64EncodeStd(secret)
        val (code, body, error) = post(host, port, "/pair", DesktopBridgeProtocol.buildPairBody(token, secretB64, phoneName), certSha256, emptyMap(), 15_000)
        if (error != null) return Result.failure(Exception("desktop unreachable: $error"))
        return when (code) {
            200 -> {
                val desktopName = runCatching { Json.parseToJsonElement(body!!.decodeToString()).jsonObject["desktop_name"]?.jsonPrimitive?.content }.getOrNull()
                    ?: return Result.failure(Exception("desktop sent an unreadable pairing reply"))
                store.writeSecret(SECRET, secretB64)
                val p = Paired(host, port, desktopName.ifBlank { name }, certSha256.lowercase())
                DefaultTextStorage.writeTextAtomic("bridge_pairing", buildJsonObject {
                    put("host", p.host); put("port", p.port); put("name", p.name); put("cert", p.certSha256) }.toString())
                paired.value = p
                Result.success(p.name)
            }
            403 -> Result.failure(Exception("pairing rejected — wrong or expired code; check the desktop"))
            409 -> Result.failure(Exception("desktop has no open pairing window — start it with --pair"))
            else -> Result.failure(Exception("pairing failed (http $code): ${body?.decodeToString()?.take(160).orEmpty()}"))
        }
    }

    private suspend fun post(host: String, port: Int, path: String, body: ByteArray, cert: String, headers: Map<String, String>, timeoutMs: Int): Triple<Int, ByteArray?, String?> =
        suspendCancellableCoroutine { cont ->
            http!!.post(host, port, path, body, cert, headers, timeoutMs) { code, bytes, error -> if (cont.isActive) cont.resume(Triple(code, bytes, error)) }
        }

    /** Signed POST /rpc (canonical = "POST\n/rpc\n{ts}\n{sha256(body)}", HMAC-SHA256). */
    suspend fun rpc(method: String, params: Map<String, Any?> = emptyMap(), timeoutMs: Int = 15_000): Result<String> {
        val p = paired.value ?: return Result.failure(Exception("not paired — pair a desktop first"))
        if (http == null) return Result.failure(Exception("Desktop networking isn't available"))
        val secret = secrets?.readSecret(SECRET)?.let { runCatching { DesktopBridgeProtocol.b64Decode(it) }.getOrNull() }
            ?: return Result.failure(Exception("stored bridge secret is missing — unpair and re-pair"))
        val body = buildJsonObject {
            put("method", method)
            if (params.isNotEmpty()) putJsonObject("params") {
                params.forEach { (k, v) -> when (v) {
                    null -> put(k, JsonNull); is String -> put(k, v); is Int -> put(k, v); is Long -> put(k, v)
                    is Boolean -> put(k, v); is JsonElement -> put(k, v); else -> put(k, v.toString())
                } }
            }
        }.toString().encodeToByteArray()
        val ts = (PlatformTime.currentMillis() / 1000).toString()
        val signature = DesktopBridgeProtocol.signHmacSha256Base64(secret,
            DesktopBridgeProtocol.canonicalMessage("POST", "/rpc", ts, DesktopBridgeProtocol.sha256Hex(body)))
        val (code, bytes, error) = post(p.host, p.port, "/rpc", body, p.certSha256,
            mapOf("Content-Type" to "application/json", "X-Meowmba-Timestamp" to ts, "X-Meowmba-Signature" to signature), timeoutMs)
        if (error != null) return Result.failure(Exception("desktop unreachable: $error"))
        val text = bytes?.decodeToString().orEmpty()
        return when (code) {
            200 -> Result.success(text)
            401 -> Result.failure(Exception("signature rejected — clocks out of sync or secret mismatch"))
            428 -> Result.failure(Exception("desktop forgot the pairing — re-pair"))
            else -> Result.failure(Exception(runCatching { Json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                ?: "bridge error (http $code)"))
        }
    }

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject
    private fun messageOf(body: String, fallback: String) = runCatching { obj(body)["message"]?.jsonPrimitive?.content }.getOrNull() ?: fallback
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.l(k: String) = this[k]?.jsonPrimitive?.longOrNull
    private fun JsonObject.b(k: String) = this[k]?.jsonPrimitive?.booleanOrNull

    suspend fun ping() = rpc("ping").mapCatching { obj(it).l("ts") ?: error("bridge sent an unreadable pong") }
    suspend fun status() = rpc("status").mapCatching { body -> val o = obj(body)
        Status(o.s("name").orEmpty(), o.s("version").orEmpty(), o.s("platform").orEmpty(), o.l("uptime_s") ?: 0,
            o["workspace_roots"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o.s("paired_phone").orEmpty()) }
    suspend fun openApp(name: String) = rpc("open_app", mapOf("name" to name)).map { messageOf(it, "Opened $name.") }
    suspend fun openUrl(url: String) = rpc("open_url", mapOf("url" to url)).map { messageOf(it, "Opened $url.") }
    suspend fun openFile(path: String) = rpc("open_file", mapOf("path" to path)).map { messageOf(it, "Opened $path.") }
    suspend fun clipboardGet() = rpc("clipboard_get").mapCatching { obj(it).s("text") ?: error("desktop clipboard was empty or unreadable") }
    suspend fun clipboardSet(text: String) = rpc("clipboard_set", mapOf("text" to text)).map { messageOf(it, "Clipboard updated.") }
    suspend fun media(action: String, volume: Int? = null) = rpc("media", buildMap { put("action", action); volume?.let { put("volume", it.coerceIn(0, 100)) } })
        .map { messageOf(it, "Media: $action.") }
    /** PNG bytes of the desktop screen. */
    suspend fun screenshot() = rpc("screenshot", timeoutMs = 30_000).mapCatching {
        DesktopBridgeProtocol.b64Decode(obj(it).s("data_b64") ?: error("desktop returned no screenshot data")) }
    suspend fun listFiles(path: String) = rpc("list_files", mapOf("path" to path)).mapCatching { body -> val o = obj(body)
        DirListing(o.s("path") ?: path, o["entries"]?.jsonArray?.map { e -> val x = e.jsonObject
            FileEntry(x.s("name").orEmpty(), x.b("dir") ?: false, x.l("size") ?: 0) }.orEmpty(), o.b("truncated") ?: false) }
    suspend fun readFile(path: String, maxBytes: Int = 65_536) = rpc("read_file", mapOf("path" to path, "max_bytes" to maxBytes)).mapCatching { body -> val o = obj(body)
        FileContent(o.s("path") ?: path, o.l("size") ?: 0, o.s("content").orEmpty(), o.b("truncated") ?: false) }
    suspend fun writeFile(path: String, content: String) = rpc("write_file", mapOf("path" to path, "content" to content)).mapCatching { body -> val o = obj(body)
        "Saved ${o.s("path") ?: path} (${o.l("size") ?: 0} bytes)" + (o.s("backup")?.let { "; previous version kept as $it" } ?: "") }
    private fun run(body: String, name: String) = obj(body).let { o ->
        RunResult(o.s("name") ?: name, o.l("exit_code")?.toInt() ?: -1, o.s("duration_s")?.toDoubleOrNull() ?: 0.0, o.b("timed_out") ?: false, o.s("output").orEmpty()) }
    suspend fun runBuild(name: String) = rpc("run_build", mapOf("name" to name), 620_000).mapCatching { run(it, name) }
    suspend fun runScript(path: String, args: String = "", timeoutSec: Int = 60) =
        rpc("run_script", mapOf("path" to path, "args" to args, "timeout_sec" to timeoutSec), (timeoutSec + 15) * 1000).mapCatching { run(it, path) }

    private const val SECRET = "bridge_secret"
}

/** "On my computer, open Safari" / "pause music on my Mac" → one desktop action (Android desktop tools). */
object DesktopCommand {
    sealed interface Action {
        data class OpenApp(val name: String) : Action
        data class OpenUrl(val url: String) : Action
        data class Media(val action: String) : Action
        object ClipboardGet : Action
        data class Build(val name: String) : Action
    }

    private val where = "(?:my (?:computer|mac|desktop|pc|laptop)|the (?:computer|mac|desktop))"

    fun parse(text: String): Action? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        val lead = Regex("^(?:please )?(?:on|in) $where[,:]? (.+)$", RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues?.get(1)
        val tail = Regex("^(?:please )?(.+?) on $where$", RegexOption.IGNORE_CASE).matchEntire(t)?.groupValues?.get(1)
        val cmd = (lead ?: tail)?.trim() ?: return null
        Regex("^(?:open|go to) (https?://\\S+)$", RegexOption.IGNORE_CASE).matchEntire(cmd)?.let { return Action.OpenUrl(it.groupValues[1]) }
        Regex("^(play|pause|resume|next|skip)(?: (?:the )?(?:music|song|track|media))?$", RegexOption.IGNORE_CASE).matchEntire(cmd)?.let {
            return Action.Media(when (it.groupValues[1].lowercase()) { "resume" -> "play"; "skip" -> "next"; else -> it.groupValues[1].lowercase() })
        }
        if (Regex("^(?:what(?:'s| is) (?:on )?(?:the |my )?clipboard|get (?:the |my )?clipboard)$", RegexOption.IGNORE_CASE).matches(cmd)) return Action.ClipboardGet
        Regex("^run (?:the )?(?:build|command) (\\S+)$", RegexOption.IGNORE_CASE).matchEntire(cmd)?.let { return Action.Build(it.groupValues[1]) }
        Regex("^(?:open|launch|start) (?:the )?(.{1,60})$", RegexOption.IGNORE_CASE).matchEntire(cmd)?.let { return Action.OpenApp(it.groupValues[1].removeSuffix(" app").trim()) }
        return null
    }
}
