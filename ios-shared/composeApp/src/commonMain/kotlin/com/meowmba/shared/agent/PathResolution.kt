package com.meowmba.shared.agent

/**
 * Pure path-text helpers used to canonicalise a path that does not exist yet.
 *
 * These live in common code, not inside the iOS actual that calls them, for one
 * reason: this is the sandbox's escape check, and code in an `actual` cannot be
 * executed on the machine this port is built on. Putting the string logic here
 * means it is unit-tested on the JVM even though only one platform needs it.
 *
 * The JVM/Android actuals use `java.io.File.canonicalPath` and never call these;
 * iOS has to emulate it because `realpath(3)` fails with ENOENT the moment any
 * component is missing, while `canonicalPath` happily resolves a path to a file
 * that has not been created yet.
 */

/**
 * Appends [tail] (already in order) to [base], collapsing `.` and `..`.
 *
 * `..` stops at the filesystem root rather than escaping above it. The root
 * marker is tracked separately from the segments precisely because of that: if
 * `/` were represented as a leading empty segment, popping to it would leave an
 * empty list that joins to `""` — losing the slash and turning the root into a
 * *relative* path, which is the one answer the sandbox must never produce.
 */
internal fun appendCollapsing(base: String, tail: List<String>): String {
    val absolute = base.startsWith('/')
    val out = mutableListOf<String>()
    for (segment in base.split('/') + tail) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out.add(segment)
        }
    }
    val joined = out.joinToString("/")
    return if (absolute) "/$joined" else joined
}

/**
 * Collapses [path] with no base to resolve against. Used when not even the
 * first component exists, so there is no canonical prefix to build on.
 */
internal fun collapseLexically(path: String): String = appendCollapsing(path, emptyList())
