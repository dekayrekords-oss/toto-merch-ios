package com.meowmba.shared.core

/**
 * Indirection over [AppStorage] so stores can be unit-tested without touching
 * the real filesystem. Production code uses [DefaultTextStorage]; tests inject
 * an in-memory fake.
 */
interface TextStorage {
    fun readText(name: String): String?
    fun writeText(name: String, content: String)

    /** Crash-safe variant of [writeText]; see [AppStorage.writeTextAtomic]. */
    fun writeTextAtomic(name: String, content: String)

    fun delete(name: String)
}

/** The real, platform-backed store. */
object DefaultTextStorage : TextStorage {
    override fun readText(name: String): String? = AppStorage.readText(name)
    override fun writeText(name: String, content: String) = AppStorage.writeText(name, content)
    override fun writeTextAtomic(name: String, content: String) =
        AppStorage.writeTextAtomic(name, content)
    override fun delete(name: String) = AppStorage.delete(name)
}

/** In-memory [TextStorage] for tests and previews. */
class MemoryTextStorage(initial: Map<String, String> = emptyMap()) : TextStorage {
    private val values = initial.toMutableMap()
    override fun readText(name: String): String? = values[name]
    override fun writeText(name: String, content: String) { values[name] = content }

    // Atomicity is meaningless for a map, so this is the same write. Tests that
    // care about the *durability* contract must target a real filesystem
    // (jvmTest), not this fake.
    override fun writeTextAtomic(name: String, content: String) { values[name] = content }

    override fun delete(name: String) { values.remove(name) }
    /** Test helper: raw view of what was persisted. */
    fun snapshot(): Map<String, String> = values.toMap()
}
