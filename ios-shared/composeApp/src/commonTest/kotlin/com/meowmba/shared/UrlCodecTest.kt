package com.meowmba.shared

import com.meowmba.shared.voice.UrlCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [UrlCodec] is **port-only**: it has no counterpart in the original, because the
 * original called `java.net.URLEncoder` / `URLDecoder` directly. Port-only code
 * cannot count toward coverage, so it is easy to leave it untested — but it is a
 * stand-in for a platform API, which is exactly the kind of code that needs a
 * test, because nothing else will notice when it drifts.
 *
 * It had none until this test. Every expected string below is the JDK's own
 * output, produced by a throwaway `java.net.URLEncoder` program rather than
 * recalled from documentation — which is how the `~` divergence in
 * [encodeQueryKeepsExactlyWhatUrlEncoderKeeps] was found.
 *
 * Writing it also found a **hang**: `decode`'s `'+'` branch did not advance its
 * index, so it appended spaces until the heap ran out. Two tests here failed with
 * `OutOfMemoryError` before that was fixed. Any input containing a `+` — the most
 * common character in a form-encoded query — never returned. That is the whole
 * argument for testing a port-only stand-in for a platform API: nothing else in
 * the suite was ever going to notice.
 */
class UrlCodecTest {

    @Test
    fun encodeQueryKeepsExactlyWhatUrlEncoderKeeps() {
        assertEquals("turn+off+the+lights", UrlCodec.encodeQuery("turn off the lights"))
        // The JDK keeps '*', '-', '_', '.' and encodes '~' as %7E. This port kept
        // '~' until a port of AssistantRelay exercised the substitution; the
        // assertions here are the ones that would have caught it.
        assertEquals("a%7Eb", UrlCodec.encodeQuery("a~b"))
        assertEquals("a*b", UrlCodec.encodeQuery("a*b"))
        assertEquals("a-b_c.d", UrlCodec.encodeQuery("a-b_c.d"))
        assertEquals("caf%C3%A9", UrlCodec.encodeQuery("café"))
        assertEquals("100%25", UrlCodec.encodeQuery("100%"))
        assertEquals("a%2Fb%3Fc%3Dd%26e", UrlCodec.encodeQuery("a/b?c=d&e"))
        assertEquals("", UrlCodec.encodeQuery(""))
    }

    @Test
    fun encodePathIsQueryEncodingWithPercentTwentySpaces() {
        assertEquals("a%20b", UrlCodec.encodePath("a b"))
        // A literal '+' must survive: it is encoded to %2B *before* the space
        // replacement runs, so the replacement cannot corrupt it.
        assertEquals("a%2Bb%20c", UrlCodec.encodePath("a+b c"))
        assertEquals("org%2Fmodel", UrlCodec.encodePath("org/model"))
    }

    @Test
    fun decodeIsTheInverseOfEncodeForQueryComponents() {
        assertEquals("turn off the lights", UrlCodec.decode("turn+off+the+lights"))
        assertEquals("café", UrlCodec.decode("caf%C3%A9"))
        assertEquals("a~b", UrlCodec.decode("a%7Eb"))
        assertEquals("", UrlCodec.decode(""))
        assertEquals("turn off the lights", UrlCodec.decode(UrlCodec.encodeQuery("turn off the lights")))
    }

    /**
     * `decode` is a **form** decoder: a bare `+` means a space, which is correct
     * for a query component and wrong for a path component, where `+` is a
     * literal plus. So `decode` must not be handed a raw path string — and,
     * because [encodePath] never emits a bare `+` (it encodes a literal `+` to
     * `%2B` *before* turning spaces into `%20`), round-tripping through
     * `encodePath` is nonetheless safe. Both halves of that are pinned here,
     * because the obvious reading — "decode is not an inverse, so the round trip
     * is lossy" — is the wrong one.
     */
    @Test
    fun decodeIsAFormDecoderSoARawPlusBecomesASpace() {
        assertEquals("a b", UrlCodec.decode("a+b"))
        assertEquals("a b", UrlCodec.decode("a%20b"))
        assertEquals("a+b", UrlCodec.decode("a%2Bb"))
        // Round trips that go through an encoder are lossless...
        assertEquals("a b", UrlCodec.decode(UrlCodec.encodeQuery("a b")))
        assertEquals("a+b c", UrlCodec.decode(UrlCodec.encodePath("a+b c")))
        // ...but decoding a raw path that contains a literal '+' is not.
        assertNotEquals("a+b", UrlCodec.decode("a+b"))
    }

    @Test
    fun malformedEscapesAreKeptRatherThanDropped() {
        // A trailing or non-hex escape is emitted literally instead of throwing,
        // so a corrupt string round-trips rather than vanishing.
        assertEquals("a%2", UrlCodec.decode("a%2"))
        assertEquals("a%zz", UrlCodec.decode("a%zz"))
        assertEquals("%", UrlCodec.decode("%"))
    }
}
