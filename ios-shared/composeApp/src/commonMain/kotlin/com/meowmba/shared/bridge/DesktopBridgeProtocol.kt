package com.meowmba.shared.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire protocol for the Meowmba desktop bridge (Phase 6 P6a).
 * Ported from `core/bridge/DesktopBridgeProtocol.kt`; the JVM crypto calls
 * moved behind [BridgeCrypto] so the same code compiles for iOS.
 *
 * Mirrors bridge/meowmba-bridge.py exactly:
 *
 *  - Pairing payload: base64url(JSON {v, host, port, name, pair_token, cert_sha256})
 *  - Request signing: base64-std(HMAC-SHA256(secret, canonical)) where
 *    canonical = "POST\n/rpc\n{unixSeconds}\n{sha256hex(rawBody)}"
 *  - Daemon rejects timestamps more than 60 s out (replay window).
 *
 * Base64 is hand-rolled so it behaves identically on every target.
 */
object DesktopBridgeProtocol {

    // ------------------------------------------------------------------
    // Pairing payload
    // ------------------------------------------------------------------

    data class PairingPayload(
        val v: Int,
        val host: String,
        val port: Int,
        val name: String,
        val pairToken: String,
        val certSha256: String
    )

    /** Parse the payload string printed by `meowmba-bridge.py --pair`. */
    fun decodePairingPayload(blob: String): Result<PairingPayload> {
        val trimmed = blob.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("payload is empty"))
        val raw = try {
            b64Decode(trimmed)
        } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("payload is not valid base64: ${e.message}"))
        }
        val obj = try {
            Json.parseToJsonElement(raw.decodeToString()).jsonObject
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("payload is not a bridge pairing payload"))
        }
        fun str(key: String): String = obj[key]?.jsonPrimitive?.content ?: ""
        val parsed = try {
            PairingPayload(
                v = obj["v"]?.jsonPrimitive?.int ?: -1,
                host = str("host"),
                port = obj["port"]?.jsonPrimitive?.int ?: -1,
                name = str("name"),
                pairToken = str("pair_token"),
                certSha256 = str("cert_sha256")
            )
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("payload is not a bridge pairing payload"))
        }
        return when {
            parsed.v != 1 ->
                Result.failure(IllegalArgumentException("unsupported payload version ${parsed.v} (expected 1)"))
            parsed.host.isBlank() ->
                Result.failure(IllegalArgumentException("payload has no host"))
            parsed.port !in 1..65535 ->
                Result.failure(IllegalArgumentException("payload port ${parsed.port} is invalid"))
            parsed.pairToken.isBlank() ->
                Result.failure(IllegalArgumentException("payload has no pairing token"))
            !parsed.certSha256.matches(Regex("[0-9a-fA-F]{64}")) ->
                Result.failure(IllegalArgumentException("payload certificate fingerprint is malformed"))
            else -> Result.success(parsed)
        }
    }

    // ------------------------------------------------------------------
    // Discovery packets (UDP broadcast from the daemon)
    // ------------------------------------------------------------------

    data class DiscoveredBridge(
        val host: String,
        val port: Int,
        val name: String,
        val certSha256: String,
        val version: String,
        val pairingOpen: Boolean
    )

    /**
     * Parse one discovery packet. [senderIp] is the UDP source address (the
     * packet itself carries no host — the network tells the truth).
     * Returns null for anything that isn't a meowmba-bridge announcement.
     */
    fun parseDiscoveryPacket(jsonText: String, senderIp: String): DiscoveredBridge? {
        val obj = try {
            Json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            return null
        }
        if (obj["service"]?.jsonPrimitive?.content != "meowmba-bridge") return null
        val port = obj["port"]?.jsonPrimitive?.int ?: return null
        val fp = obj["cert_sha256"]?.jsonPrimitive?.content ?: return null
        if (!fp.matches(Regex("[0-9a-fA-F]{64}"))) return null
        return DiscoveredBridge(
            host = senderIp,
            port = port,
            name = obj["name"]?.jsonPrimitive?.content ?: "desktop",
            certSha256 = fp.lowercase(),
            version = obj["version"]?.jsonPrimitive?.content ?: "",
            pairingOpen = obj["pairing_open"]?.jsonPrimitive?.boolean ?: false
        )
    }

    // ------------------------------------------------------------------
    // Signing
    // ------------------------------------------------------------------

    /** Canonical string the daemon HMACs: "POST\n/rpc\n{ts}\n{sha256hex(body)}". */
    fun canonicalMessage(method: String, path: String, timestamp: String, bodySha256Hex: String): String =
        "$method\n$path\n$timestamp\n$bodySha256Hex"

    fun sha256Hex(bytes: ByteArray): String = BridgeCrypto.sha256Hex(bytes)

    /** Build a JSON body for a standard pairing request (with token/code). */
    fun buildPairBody(token: String, secretB64: String, phoneName: String): ByteArray =
        "{\"token\":\"$token\",\"secret\":\"$secretB64\",\"phone_name\":\"${phoneName.replace("\"", "\\\"")}\"}".encodeToByteArray()

    /**
     * Build a JSON body for an auto-pair request (USB tether — no code).
     * The daemon accepts this when the client IP is on the USB subnet.
     */
    fun buildAutoPairBody(secretB64: String, phoneName: String): ByteArray =
        "{\"usb_auto\":true,\"secret\":\"$secretB64\",\"phone_name\":\"${phoneName.replace("\"", "\\\"")}\"}".encodeToByteArray()

    fun signHmacSha256Base64(secret: ByteArray, message: String): String =
        b64EncodeStd(BridgeCrypto.hmacSha256(secret, message.encodeToByteArray()))

    // ------------------------------------------------------------------
    // Base64 (hand-rolled; identical alphabet/behavior on every target)
    // ------------------------------------------------------------------

    private const val STD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 with padding (matches Python's base64.b64encode). */
    fun b64EncodeStd(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8) or (data[i + 2].toInt() and 0xFF)
            out.append(STD_ALPHABET[n shr 18 and 0x3F]).append(STD_ALPHABET[n shr 12 and 0x3F])
                .append(STD_ALPHABET[n shr 6 and 0x3F]).append(STD_ALPHABET[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].toInt() and 0xFF shl 16
                out.append(STD_ALPHABET[n shr 18 and 0x3F]).append(STD_ALPHABET[n shr 12 and 0x3F]).append("==")
            }
            2 -> {
                val n = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
                out.append(STD_ALPHABET[n shr 18 and 0x3F]).append(STD_ALPHABET[n shr 12 and 0x3F])
                    .append(STD_ALPHABET[n shr 6 and 0x3F]).append("=")
            }
        }
        return out.toString()
    }

    /** Base64 decode accepting standard or URL-safe alphabet; padding optional. */
    fun b64Decode(s: String): ByteArray {
        val table = IntArray(128) { -1 }
        for (idx in STD_ALPHABET.indices) table[STD_ALPHABET[idx].code] = idx
        table['-'.code] = 62
        table['_'.code] = 63

        val clean = s.trimEnd('=')
        if (clean.length % 4 == 1) throw IllegalArgumentException("invalid length ${s.length}")
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            if (v < 0) throw IllegalArgumentException("invalid character '$c'")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
