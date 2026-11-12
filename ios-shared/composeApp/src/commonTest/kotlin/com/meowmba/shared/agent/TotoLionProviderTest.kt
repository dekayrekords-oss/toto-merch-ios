package com.meowmba.shared.agent

import com.meowmba.shared.bridge.PinnedHttpDriver
import com.meowmba.shared.bridge.SecretStoreDriver
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent

class TotoLionProviderTest {
    private val pin = "a".repeat(64)
    private class Secrets(private val token: String? = "tok") : SecretStoreDriver {
        override fun readSecret(key: String) = token
        override fun writeSecret(key: String, value: String) {}
        override fun deleteSecret(key: String) {}
    }
    private class Http(private val status: Int = 200, private val payload: ByteArray, private val fail: String? = null) : PinnedHttpDriver {
        var host = ""; var path = ""; var cert = ""; var headers = emptyMap<String, String>()
        override fun post(host: String, port: Int, path: String, body: ByteArray, certSha256: String, headers: Map<String, String>, timeoutMs: Int, onResult: (Int, ByteArray?, String?) -> Unit) {
            this.host = host; this.path = path; this.cert = certSha256; this.headers = headers; onResult(status, payload, fail)
        }
        override fun randomBytes(count: Int) = ByteArray(count)
    }
    private fun provider(http: Http, secrets: Secrets = Secrets()) = TotoLionProvider("toto.local", 8445, pin, http = http, secrets = secrets)
    @Test fun validPinnedRequestUsesBearerAndReply() = runTest {
        val h = Http(payload = "{\"model\":\"lion\",\"reply\":\"hello\",\"motion_enabled\":false}".encodeToByteArray())
        assertEquals(ModelResponse.Complete("hello"), provider(h).generate("hi")); assertEquals("/chat", h.path); assertEquals("Bearer tok", h.headers["Authorization"]); assertEquals(pin, h.cert)
    }
    @Test fun motionMustBeExplicitlyFalse() = runTest {
        val h = Http(payload = "{\"model\":\"lion\",\"reply\":\"x\",\"motion_enabled\":true}".encodeToByteArray())
        assertTrue(provider(h).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = "{\"model\":\"lion\",\"reply\":\"x\"}".encodeToByteArray())).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = "{\"model\":\"lion\",\"reply\":\"x\",\"motion_enabled\":\"false\"}".encodeToByteArray())).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = "not-json".encodeToByteArray())).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = "{\"model\":3,\"reply\":\"x\",\"motion_enabled\":false}".encodeToByteArray())).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = "{\"model\":\"lion\",\"reply\":3,\"motion_enabled\":false}".encodeToByteArray())).generate("x") is ModelResponse.Error)
    }
    @Test fun invalidPinAndMissingTokenFailBeforeNetwork() = runTest {
        val h = Http(payload = ByteArray(0)); assertTrue(TotoLionProvider("toto.local", 8445, "bad", http = h, secrets = Secrets()).generate("x") is ModelResponse.Error)
        assertTrue(provider(h, Secrets(null)).generate("x") is ModelResponse.Error)
    }
    @Test fun non2xxAndOversizedResponsesFail() = runTest {
        assertTrue(provider(Http(500, ByteArray(0))).generate("x") is ModelResponse.Error)
        assertTrue(provider(Http(payload = ByteArray(64 * 1024 + 1))).generate("x") is ModelResponse.Error)
    }
    @Test fun bearerControlCharactersAreRejectedBeforeDriver() = runTest {
        val h = Http(payload = ByteArray(0)); val result = TotoLionProvider("toto.local", 8445, pin, http = h, secrets = Secrets("bad\r\nvalue")).generate("x")
        assertTrue(result is ModelResponse.Error); assertEquals("", h.path)
    }
    @Test fun cancellationRemainsCancellationAndLateCallbacksAreIgnored() = runTest {
        var callback: ((Int, ByteArray?, String?) -> Unit)? = null
        val http = object : PinnedHttpDriver {
            override fun post(host: String, port: Int, path: String, body: ByteArray, certSha256: String, headers: Map<String, String>, timeoutMs: Int, onResult: (Int, ByteArray?, String?) -> Unit) { callback = onResult }
            override fun randomBytes(count: Int) = ByteArray(count)
        }
        var finished = false
        val job = launch { TotoLionProvider("lion.local", 8445, pin, http = http, secrets = Secrets()).generate("hello"); finished = true }
        runCurrent(); assertNotNull(callback); job.cancelAndJoin()
        callback!!(200, "{\"model\":\"lion\",\"reply\":\"late\",\"motion_enabled\":false}".encodeToByteArray(), null)
        assertTrue(job.isCancelled); assertFalse(finished)
    }
    @Test fun errorsNeverEchoUntrustedTransportOrParserText() = runTest {
        val marker = "private-server-detail"
        val transport = provider(Http(payload = ByteArray(0), fail = marker)).generate("hi") as ModelResponse.Error
        assertFalse(transport.message.contains(marker))
        val malformed = provider(Http(payload = marker.encodeToByteArray())).generate("hi") as ModelResponse.Error
        assertEquals("Invalid Toto Lion response", malformed.message)
    }

}
