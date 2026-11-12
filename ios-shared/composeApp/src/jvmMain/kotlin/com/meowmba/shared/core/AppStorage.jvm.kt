package com.meowmba.shared.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JVM actual: a dot-directory under the user's home. */
actual object AppStorage {

    private val dir: File by lazy {
        File(System.getProperty("user.home") ?: ".", ".meowmba").apply { mkdirs() }
    }

    private fun file(name: String) = File(dir, name)

    actual fun readText(name: String): String? {
        val target = file(name)
        if (!target.isFile) return null
        return runCatching { target.readText() }.getOrNull()
    }

    actual fun writeText(name: String, content: String) {
        dir.mkdirs()
        file(name).writeText(content)
    }

    actual fun writeTextAtomic(name: String, content: String) {
        dir.mkdirs()
        val pending = File(dir, "$name.pending")
        // fsync before the rename: without it the rename can land while the
        // bytes are still in the page cache, so a power loss leaves an empty
        // or truncated file under the *good* name.
        FileOutputStream(pending).use { out ->
            out.write(content.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        Files.move(
            pending.toPath(),
            file(name).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    actual fun delete(name: String) {
        file(name).delete()
    }

    actual fun location(): String = dir.absolutePath
}
