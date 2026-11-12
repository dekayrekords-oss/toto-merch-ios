package com.meowmba.shared

import com.meowmba.shared.bridge.DesktopBridgeProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeProtocolTest {

    @Test
    fun base64StdMatchesKnownVectors() {
        assertEquals("", DesktopBridgeProtocol.b64EncodeStd(byteArrayOf()))
        assertEquals("Zg==", DesktopBridgeProtocol.b64EncodeStd("f".encodeToByteArray()))
        assertEquals("Zm8=", DesktopBridgeProtocol.b64EncodeStd("fo".encodeToByteArray()))
        assertEquals("Zm9v", DesktopBridgeProtocol.b64EncodeStd("foo".encodeToByteArray()))
        assertEquals("Zm9vYmFy", DesktopBridgeProtocol.b64EncodeStd("foobar".encodeToByteArray()))
    }

    @Test
    fun base64RoundTripIncludingUrlSafe() {
        val data = ByteArray(64) { it.toByte() }
        val encoded = DesktopBridgeProtocol.b64EncodeStd(data)
        assertTrue(data.contentEquals(DesktopBridgeProtocol.b64Decode(encoded)))
        // URL-safe alphabet accepted too
        val urlSafe = encoded.replace('+', '-').replace('/', '_')
        assertTrue(data.contentEquals(DesktopBridgeProtocol.b64Decode(urlSafe)))
        // Padding optional
        assertTrue(data.contentEquals(DesktopBridgeProtocol.b64Decode(encoded.trimEnd('='))))
    }

    @Test
    fun pairingPayloadRoundTrip() {
        // Build the same JSON shape the Python daemon prints, b64url-encoded
        val json = """{"v":1,"host":"192.168.1.10","port":8765,"name":"Mac","pair_token":"tok123","cert_sha256":"${"a".repeat(64)}"}"""
        val b64 = DesktopBridgeProtocol.b64EncodeStd(json.encodeToByteArray())
            .replace('+', '-').replace('/', '_')
        val result = DesktopBridgeProtocol.decodePairingPayload(b64)
        val payload = result.getOrThrow()
        assertEquals(1, payload.v)
        assertEquals("192.168.1.10", payload.host)
        assertEquals(8765, payload.port)
        assertEquals("Mac", payload.name)
        assertEquals("tok123", payload.pairToken)
        assertEquals("a".repeat(64), payload.certSha256)
    }

    @Test
    fun pairingPayloadRejectsGarbage() {
        assertTrue(DesktopBridgeProtocol.decodePairingPayload("").isFailure)
        assertTrue(DesktopBridgeProtocol.decodePairingPayload("!!!not-base64!!!").isFailure)
        // valid base64, invalid JSON
        assertTrue(DesktopBridgeProtocol.decodePairingPayload("Zm9v").isFailure)
        // wrong version
        val v2 = """{"v":2,"host":"h","port":1,"name":"n","pair_token":"t","cert_sha256":"${"a".repeat(64)}"}"""
        val v2Result = DesktopBridgeProtocol.decodePairingPayload(
            DesktopBridgeProtocol.b64EncodeStd(v2.encodeToByteArray())
        )
        assertTrue(v2Result.isFailure)
        assertTrue("unsupported payload version" in (v2Result.exceptionOrNull()?.message ?: ""))
    }

    @Test
    fun discoveryPacketParsesAndRejects() {
        val packet = """{"service":"meowmba-bridge","port":8765,"name":"Dev Mac","version":"0.7","pairing_open":true,"cert_sha256":"${"AB".repeat(32)}"}"""
        val bridge = DesktopBridgeProtocol.parseDiscoveryPacket(packet, senderIp = "10.0.0.5")
        assertEquals("10.0.0.5", bridge?.host)
        assertEquals(8765, bridge?.port)
        assertEquals("Dev Mac", bridge?.name)
        assertEquals("ab".repeat(32), bridge?.certSha256)   // lowercased
        assertTrue(bridge?.pairingOpen == true)

        assertNull(DesktopBridgeProtocol.parseDiscoveryPacket("""{"service":"other"}""", "1.2.3.4"))
        assertNull(DesktopBridgeProtocol.parseDiscoveryPacket("garbage", "1.2.3.4"))
        assertNull(DesktopBridgeProtocol.parseDiscoveryPacket("""{"service":"meowmba-bridge"}""", "1.2.3.4"))
    }

    @Test
    fun canonicalMessageFormat() {
        assertEquals(
            "POST\n/rpc\n1700000000\nabc123",
            DesktopBridgeProtocol.canonicalMessage("POST", "/rpc", "1700000000", "abc123")
        )
    }

    @Test
    fun hmacMatchesKnownRfcVector() {
        // RFC 6231-style test vector: HMAC-SHA256(key="key", msg="The quick brown fox jumps over the lazy dog")
        val hex = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"
        val expectedBase64 = DesktopBridgeProtocol.b64EncodeStd(hexToBytes(hex))
        assertEquals(
            expectedBase64,
            DesktopBridgeProtocol.signHmacSha256Base64("key".encodeToByteArray(), "The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun pairBodiesMatchDaemonContract() {
        assertEquals(
            """{"token":"t1","secret":"s3c","phone_name":"Pixel 9"}""",
            DesktopBridgeProtocol.buildPairBody("t1", "s3c", "Pixel 9").decodeToString()
        )
        assertEquals(
            """{"usb_auto":true,"secret":"s3c","phone_name":"a\"b"}""",
            DesktopBridgeProtocol.buildAutoPairBody("s3c", "a\"b").decodeToString()
        )
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) + hex[i * 2 + 1].digitToInt(16)).toByte()
        }
}
