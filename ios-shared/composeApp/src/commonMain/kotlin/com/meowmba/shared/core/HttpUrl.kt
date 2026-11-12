package com.meowmba.shared.core

import com.meowmba.shared.voice.UrlCodec

/**
 * Minimal HTTP(S) URL parser replacing java.net.URI (JVM-only) for the
 * model-download layer. Supports the subset the original used: absolute
 * http/https URLs, relative resolution for redirect targets, and query
 * parameter access. Rejects anything structurally wrong with null.
 */
class HttpUrl private constructor(
    val scheme: String,
    val host: String?,
    val port: Int?,
    val path: String,
    val rawQuery: String?,
    val rawUserInfo: String?,
    val fragment: String?,
) {
    val defaultPort: Int get() = if (scheme.equals("https", true)) 443 else 80
    val effectivePort: Int get() = port ?: defaultPort
    val isHttp: Boolean get() = scheme.equals("http", true) || scheme.equals("https", true)

    /** Query parameters, percent-decoded, first value wins (original semantics). */
    fun queryParams(): Map<String, String> {
        val query = rawQuery ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = UrlCodec.decode(if (eq < 0) pair else pair.substring(0, eq))
            if (key in out) continue
            out[key] = UrlCodec.decode(if (eq < 0) "" else pair.substring(eq + 1))
        }
        return out
    }

    /** Resolve a relative (or absolute) reference against this URL. */
    fun resolve(reference: String): HttpUrl? {
        val ref = reference.trim()
        if (ref.isEmpty()) return null
        parse(ref)?.let { return it }
        if (ref.startsWith("#")) {
            return copyWith(fragment = ref.substring(1).takeIf { it.isNotEmpty() }, rawQuery = rawQuery)
        }
        val newPath: String
        val newQuery: String?
        if (ref.startsWith("/")) {
            newPath = ref
            newQuery = null
        } else {
            val qIdx = ref.indexOfFirst { it == '?' || it == '#' }
            val bare = if (qIdx < 0) ref else ref.substring(0, qIdx)
            val q = if (qIdx < 0) null else ref.substring(qIdx)
            val baseDir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }
            newPath = normalizePath("$baseDir/$bare")
            newQuery = q?.let { if (it.startsWith("?")) it.substring(1) else it.substring(1) }
        }
        return copyWith(path = newPath, rawQuery = newQuery ?: rawQuery, fragment = null)
    }

    private fun copyWith(
        path: String = this.path,
        rawQuery: String? = this.rawQuery,
        fragment: String? = this.fragment,
    ) = HttpUrl(scheme, host, port, path, rawQuery, rawUserInfo, fragment)

    override fun toString(): String {
        val sb = StringBuilder(scheme).append("://")
        if (!rawUserInfo.isNullOrEmpty()) sb.append(rawUserInfo).append('@')
        sb.append(host ?: "")
        if (port != null) sb.append(':').append(port)
        sb.append(if (path.startsWith('/')) path else "/$path")
        if (!rawQuery.isNullOrEmpty()) sb.append('?').append(rawQuery)
        if (!fragment.isNullOrEmpty()) sb.append('#').append(fragment)
        return sb.toString()
    }

    companion object {
        fun parse(raw: String): HttpUrl? {
            val value = raw.trim()
            val schemeEnd = value.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = value.substring(0, schemeEnd).lowercase()
            if (!isValidScheme(scheme)) return null
            var rest = value.substring(schemeEnd + 3)

            val hashIdx = rest.indexOf('#')
            val fragment = if (hashIdx >= 0) rest.substring(hashIdx + 1) else null
            if (hashIdx >= 0) rest = rest.substring(0, hashIdx)

            val queryIdx = rest.indexOf('?')
            val rawQuery = if (queryIdx >= 0) rest.substring(queryIdx + 1) else null
            if (queryIdx >= 0) rest = rest.substring(0, queryIdx)

            val authorityAndPath = rest
            val slashIdx = authorityAndPath.indexOf('/')
            val authority = if (slashIdx >= 0) authorityAndPath.substring(0, slashIdx) else authorityAndPath
            val path = if (slashIdx >= 0) authorityAndPath.substring(slashIdx) else ""

            val atIdx = authority.lastIndexOf('@')
            val rawUserInfo = if (atIdx >= 0) authority.substring(0, atIdx) else null
            val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

            val host: String
            var port: Int? = null
            if (hostPort.startsWith("[") && hostPort.contains("]")) {
                host = hostPort.substringBefore(']').substring(1)
                val after = hostPort.substringAfter(']', "")
                if (after.startsWith(":")) port = after.substring(1).toIntOrNull()
            } else if (hostPort.count { it == ':' } == 1) {
                val h = hostPort.substringBefore(':')
                host = h
                val p = hostPort.substringAfter(':')
                if (p.isNotEmpty()) port = p.toIntOrNull() ?: return null
            } else {
                host = hostPort
            }
            if (host.isEmpty()) return null
            if (port != null && (port < 0 || port > 65535)) return null
            if (!isValidHost(host)) return null
            return HttpUrl(scheme, host, port, if (path.isEmpty()) "/" else path, rawQuery, rawUserInfo, fragment)
        }

        private fun isValidScheme(s: String): Boolean = s.matches(Regex("[a-zA-Z][a-zA-Z0-9+.-]*"))
        private fun isValidHost(h: String): Boolean =
            h.isNotEmpty() && h.length <= 253 && h.none { it.code < 32 }

        /** Collapse '.'/'..' segments; the root never rises above '/'. */
        fun normalizePath(path: String): String {
            val absolute = path.startsWith('/')
            val segments = ArrayList<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                    else -> segments.add(part)
                }
            }
            val joined = segments.joinToString("/")
            return when {
                !absolute -> joined
                joined.isEmpty() -> "/"
                else -> "/$joined"
            }
        }
    }
}
