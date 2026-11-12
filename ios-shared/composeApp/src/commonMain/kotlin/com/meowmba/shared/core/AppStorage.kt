package com.meowmba.shared.core

/**
 * Minimal file-backed persistence seam. `java.io.File` is JVM-only and
 * `NSUserDefaults` has no Android equivalent, so the app's durable state
 * (provider settings, conversation history) goes through this one contract.
 *
 * Actuals:
 *  - JVM     -> `~/.meowmba/`
 *  - Android -> `context.filesDir/meowmba/` (call [AppStorage.init] first)
 *  - iOS     -> `<Documents>/meowmba/`
 *
 * Names are flat file names, never paths. Callers own their serialization;
 * this layer only moves UTF-8 text in and out.
 */
expect object AppStorage {
    /** Reads [name] as UTF-8 text, or null when it does not exist. */
    fun readText(name: String): String?

    /** Writes [name] as UTF-8 text, creating the backing directory if needed. */
    fun writeText(name: String, content: String)

    /**
     * Writes [name] durably: stage to a sibling temp file, flush it to the
     * storage device, then rename it over the target.
     *
     * [writeText] truncates the target in place, so a crash or a full disk
     * mid-write leaves a *partial* file where the previous good one used to be.
     * That is fine for a cache and not fine for user data, which is why this
     * variant exists. On a POSIX filesystem `rename` within one directory is
     * atomic, so a reader sees either the old contents or the new — never a
     * half-written file.
     *
     * Costs a second file and an fsync, so prefer [writeText] for anything
     * reconstructible.
     */
    fun writeTextAtomic(name: String, content: String)

    /** Deletes [name]. A no-op when it is already absent. */
    fun delete(name: String)

    /** Absolute path of the backing directory. Diagnostics only. */
    fun location(): String
}
