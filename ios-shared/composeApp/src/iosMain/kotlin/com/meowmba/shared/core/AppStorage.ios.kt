@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.meowmba.shared.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.refTo
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.rename

/**
 * iOS actual: `<Documents>/meowmba/`. Documents (not Caches) because provider
 * settings and conversation history must survive a low-storage purge.
 *
 * Mirrors the POSIX read/write approach already used by `ModelFile.ios.kt`
 * rather than NSString file APIs, so byte handling stays explicit.
 */
actual object AppStorage {

    private val manager = NSFileManager.defaultManager

    private val dir: String by lazy {
        val documents = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        ).firstOrNull() as? String ?: "."
        val path = "$documents/meowmba"
        if (!manager.fileExistsAtPath(path)) {
            manager.createDirectoryAtPath(path, true, null, null)
        }
        path
    }

    private fun pathFor(name: String) = "$dir/$name"

    actual fun readText(name: String): String? {
        val path = pathFor(name)
        if (!manager.fileExistsAtPath(path)) return null
        val handle: CPointer<FILE> = fopen(path, "rb") ?: return null
        try {
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(8192)
            while (true) {
                // refTo never returns null, so the safe call and the elvis fallback
                // were both dead weight (fread returns a plain ULong).
                val read = fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle)
                if (read == 0uL) break
                chunks += buffer.copyOf(read.toInt())
            }
            if (chunks.isEmpty()) return ""
            val all = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(all, offset)
                offset += chunk.size
            }
            // Decode once at the end: per-chunk decoding would corrupt any
            // multi-byte character straddling a buffer boundary.
            return all.decodeToString()
        } finally {
            fclose(handle)
        }
    }

    actual fun writeText(name: String, content: String) {
        val bytes = content.encodeToByteArray()
        val handle: CPointer<FILE> = fopen(pathFor(name), "wb")
            ?: error("Could not open ${pathFor(name)} for writing")
        try {
            if (bytes.isNotEmpty()) {
                fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), handle)
            }
        } finally {
            fclose(handle)
        }
    }

    actual fun writeTextAtomic(name: String, content: String) {
        val target = pathFor(name)
        val pending = "$target.pending"
        val bytes = content.encodeToByteArray()
        val handle: CPointer<FILE> = fopen(pending, "wb")
            ?: error("Could not open $pending for writing")
        try {
            if (bytes.isNotEmpty()) {
                fwrite(bytes.refTo(0), 1u, bytes.size.toULong(), handle)
            }
            // fflush pushes to the OS; fsync pushes to the device. Renaming
            // after only an fflush can still lose the bytes on power loss.
            fflush(handle)
            fsync(fileno(handle))
        } finally {
            fclose(handle)
        }
        // rename(2) within one directory is atomic, so a reader sees either the
        // old contents or the new -- never a half-written file.
        if (rename(pending, target) != 0) {
            error("Could not replace $target with $pending")
        }
    }

    actual fun delete(name: String) {
        val path = pathFor(name)
        if (manager.fileExistsAtPath(path)) {
            manager.removeItemAtPath(path, null)
        }
    }

    actual fun location(): String = dir
}
