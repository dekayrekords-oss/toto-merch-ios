package com.meowmba.shared.security

import com.meowmba.shared.core.HttpUrl

/**
 * HTTP is only allowed to the user's own LAN / loopback.
 *
 * Ported from `core/security/CleartextPolicy.kt` (java.net.URI replaced with
 * the KMP [HttpUrl] parser). The real gate: a public `http://` host is
 * refused so a MITM on the wide internet cannot see API keys or chat.
 */
object CleartextPolicy {

    fun isAllowed(raw: String): Boolean = denyReason(raw) == null

    fun denyReason(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        val uri = HttpUrl.parse(url) ?: return "That is not a valid URL"
        val scheme = uri.scheme.lowercase()
        if (scheme == "https") return null
        if (scheme != "http") return "Only http:// and https:// URLs are allowed"
        val host = uri.host?.lowercase()?.trim('.') ?: return "HTTP URL is missing a host"
        if (isPrivateHost(host)) return null
        return "HTTP is only allowed on your local network. Use HTTPS for anything else."
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('.')
        if (h == "localhost" || h == "localhost." || h.endsWith(".localhost")) return true
        if (h == "::1" || h == "[::1]" || h == "0.0.0.0") return true
        if (h.endsWith(".local")) return true
        val ip = parseIpv4(h) ?: return false
        val a = ip[0]
        val b = ip[1]
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /** Browser navigations that a third party must not be able to trigger. */
    fun isSafeBrowseUrl(raw: String): Boolean {
        val url = raw.trim()
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        if (lower.startsWith("about:blank") || lower.startsWith("data:text/html")) return true
        if (lower.startsWith("https://meow.local/")) return true
        if (lower.startsWith("javascript:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("android-app:")
        ) return false
        if (lower.startsWith("https://")) return true
        if (lower.startsWith("http://")) return isAllowed(url)
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }
}
