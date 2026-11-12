package com.meowmba.shared.agent

import com.meowmba.shared.core.HttpUrl
import com.meowmba.shared.security.CleartextPolicy

/**
 * Credentials belong to the Hub origin, never a custom URL or signed file CDN.
 * Ported from `core/agent/ModelDownloadPolicy.kt` (java.net.URI -> HttpUrl).
 */
object ModelDownloadPolicy {
    fun tokenForUrl(url: String, token: String?): String? {
        val uri = HttpUrl.parse(url) ?: return null
        if (uri.scheme != "https" || !uri.host.equals("huggingface.co", true) ||
            uri.port !in setOf(null, 443) || uri.rawUserInfo != null) return null
        return token?.trim()?.takeIf { value -> value.isNotBlank() && value.length <= 512 &&
            value.none { it.isWhitespace() || it.code < 32 } }
    }

    fun validateUrl(url: String): String {
        val value = url.trim()
        val uri = HttpUrl.parse(value)
        require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Use an HTTP or HTTPS model address without embedded credentials."
        }
        require(uri.port == null || uri.port in 1..65535) { "The model address has an invalid port." }
        require(CleartextPolicy.isAllowed(value)) { "HTTP model downloads are allowed only on your local network. Use HTTPS for public hosts." }
        return value
    }

    /** Relative and signed CDN redirects work, while each hop is revalidated. */
    fun redirectTarget(currentUrl: String, location: String): String {
        require(location.length <= 16_384 && location.isNotBlank()) { "Invalid model download redirect." }
        val base = HttpUrl.parse(currentUrl) ?: throw IllegalArgumentException("Invalid model download redirect.")
        val next = base.resolve(location)?.toString()
            ?: throw IllegalArgumentException("Invalid model download redirect.")
        validateUrl(next)
        val parsedNext = HttpUrl.parse(next)!!
        require(!(base.scheme == "https" && parsedNext.scheme == "http")) {
            "The model server redirected HTTPS to an insecure HTTP address."
        }
        return next
    }

    fun validFileName(name: String): Boolean = name.length in 1..255 && name != "." && name != ".." &&
        name.matches(Regex("[a-zA-Z0-9._-]+"))
}
