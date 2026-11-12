package com.meowmba.shared.voice

/**
 * KMP-safe query-string encoding (java.net.URLEncoder is JVM-only).
 *
 * Mirrors `java.net.URLEncoder` for query components: space -> '+', and only
 * `A-Za-z0-9`, '-', '_', '.' and '*' are kept; everything else is
 * percent-encoded as uppercase UTF-8 bytes.
 *
 * Note what is **not** in that kept set: `~`. RFC 3986 calls `~` unreserved, but
 * `URLEncoder` is not RFC 3986 — it emits `%7E`. This port kept `~` until a port
 * of `AssistantRelay` exercised the substitution, so `AgentRouter`'s search query
 * and `HuggingFaceLocalModels`' path segments were diverging from the original
 * for any value containing a tilde. [UrlCodecTest] now pins the JDK's own output.
 */
object UrlCodec {

    fun encodeQuery(input: String): String {
        val out = StringBuilder(input.length * 3)
        for (byte in input.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            when {
                c == ' '.code -> out.append('+')
                c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' ||
                    c.toChar() in "-_.*" -> out.append(c.toChar())
                else -> out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return out.toString()
    }

    /** URLEncoder semantics but with %20 for spaces (path components, HF URLs). */
    fun encodePath(input: String): String =
        encodeQuery(input).replace("+", "%20")

    /**
     * Percent-decode (URLDecoder semantics: '+' -> space). Bytes that are not
     * valid UTF-8 are kept as U+FFFD replacements via decodeToString default.
     * Inverse of [encodeQuery] / [encodePath].
     *
     * **Every branch must advance [i].** The `'+'` branch did not, so `decode`
     * never terminated on any input containing a `+` — it appended spaces until
     * the heap ran out. `+` is the single most common character in a
     * form-encoded query, and it reaches here from `HttpUrl.parse` and
     * `HuggingFaceLocalModels`, so this was a hang on a realistic input rather
     * than a theoretical one. It survived because [UrlCodec] is port-only code
     * with no original counterpart and therefore had no test at all;
     * `UrlCodecTest` is what surfaced it.
     */
    fun decode(input: String): String {
        val bytes = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '+' -> {
                    bytes.add(' '.code.toByte())
                    i++
                }
                c == '%' && i + 2 < input.length -> {
                    val hi = hexValue(input[i + 1])
                    val lo = hexValue(input[i + 2])
                    if (hi < 0 || lo < 0) {
                        bytes.add(c.code.toByte())
                        i++
                    } else {
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    }
                }
                else -> {
                    bytes.addAll(c.toString().encodeToByteArray().toList())
                    i++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val HEX = "0123456789ABCDEF"
}
