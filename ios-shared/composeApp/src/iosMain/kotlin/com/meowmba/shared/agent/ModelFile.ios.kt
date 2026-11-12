package com.meowmba.shared.agent

import com.meowmba.shared.core.PlatformId
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.timeIntervalSince1970
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.realpath
import platform.posix.rename

@OptIn(ExperimentalForeignApi::class)
actual class ModelFile actual constructor(path: String) {
    private val nsPath: String = path
    private val manager: NSFileManager = NSFileManager.defaultManager

    actual val path: String get() = nsPath
    actual val name: String get() = nsPath.substringAfterLast('/')
    actual fun exists(): Boolean = manager.fileExistsAtPath(nsPath)
    actual fun isDirectory(): Boolean {
        val attrs = manager.attributesOfItemAtPath(nsPath, null) ?: return false
        return (attrs["NSFileType"] as? String) == "NSFileTypeDirectory"
    }
    actual fun isFile(): Boolean = exists() && !isDirectory()
    actual fun length(): Long =
        (manager.attributesOfItemAtPath(nsPath, null)?.get("NSFileSize") as? NSNumber)?.longLongValue ?: 0L
    actual fun lastModified(): Long =
        (manager.attributesOfItemAtPath(nsPath, null)?.get("NSFileModificationDate") as? NSDate)
            ?.timeIntervalSince1970?.toLong()?.times(1000) ?: 0L
    actual fun delete(): Boolean = manager.removeItemAtPath(nsPath, null)
    actual fun mkdirs(): Boolean = manager.createDirectoryAtPath(nsPath, true, null, null)
    actual fun renameTo(target: ModelFile): Boolean = manager.moveItemAtPath(nsPath, target.path, null)
    actual fun parent(): ModelFile? =
        nsPath.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { ModelFile(it) }
    actual fun listFiles(): List<ModelFile> =
        manager.contentsOfDirectoryAtPath(nsPath, null)
            ?.mapNotNull { it as? String }
            ?.map { ModelFile(if (nsPath.endsWith('/')) "$nsPath$it" else "$nsPath/$it") }
            ?: emptyList()

    /**
     * `realpath(3)` resolves symlinks and `..`, but fails with ENOENT the moment
     * any component is missing — unlike `java.io.File.canonicalPath`, which
     * resolves the longest existing prefix and collapses the remainder
     * lexically.
     *
     * That difference matters: the sandbox canonicalises the destination of
     * `files.write` *before* the file exists. So when `realpath` fails, walk up
     * to the deepest ancestor that does resolve, then append the remaining
     * segments with `..` collapsed. The collapse helpers live in common code so
     * they can be unit-tested on the JVM; only this walk is iOS-specific.
     */
    actual fun canonicalPath(): String {
        realpath(nsPath, null)?.let { return it.toKString() }
        val tail = mutableListOf<String>()
        var cursor: ModelFile = this
        while (true) {
            val parent = cursor.parent() ?: return collapseLexically(nsPath)
            tail.add(0, cursor.name)
            val resolved = realpath(parent.path, null)
            if (resolved != null) return appendCollapsing(resolved.toKString(), tail)
            cursor = parent
        }
    }

    actual fun isSymbolicLink(): Boolean {
        val attrs = manager.attributesOfItemAtPath(nsPath, null) ?: return false
        return (attrs["NSFileType"] as? String) == "NSFileTypeSymbolicLink"
    }

    actual fun readBytes(): ByteArray? {
        if (!isFile()) return null
        val handle: CPointer<FILE> = fopen(nsPath, "rb") ?: return null
        try {
            val chunks = mutableListOf<ByteArray>()
            val buffer = ByteArray(8192)
            while (true) {
                val read = fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle)
                if (read == 0uL) break
                chunks += buffer.copyOf(read.toInt())
            }
            if (chunks.isEmpty()) return ByteArray(0)
            val all = ByteArray(chunks.sumOf { it.size })
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(all, offset)
                offset += chunk.size
            }
            return all
        } finally {
            fclose(handle)
        }
    }

    // Decode once from the full byte array: decoding per chunk would corrupt any
    // multi-byte character straddling an 8 KiB boundary.
    actual fun readText(): String? = readBytes()?.decodeToString()

    actual fun writeBytesAtomic(bytes: ByteArray) {
        val parent = parent() ?: error("No parent directory for $nsPath")
        parent.mkdirs()
        // A sibling temp file, not one in the system temp dir: rename(2) is only
        // atomic within one directory (one filesystem), and a cross-device move
        // is neither atomic nor a rename.
        val pending = "${parent.path.trimEnd('/')}/.pending-${PlatformId.shortId()}"
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
        if (rename(pending, nsPath) != 0) {
            error("Could not replace $nsPath with $pending")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual class ModelFileSink actual constructor(file: ModelFile) {
    private val fd: CPointer<FILE>? = fopen(file.path, "wb")

    init {
        check(fd != null) { "Could not open ${file.path} for writing" }
    }

    actual fun write(bytes: ByteArray, offset: Int, length: Int) {
        val chunk = if (offset == 0 && length == bytes.size) bytes else bytes.copyOfRange(offset, offset + length)
        fwrite(chunk.refTo(0), 1u, length.toULong(), fd)
    }

    actual fun flush() {
        fflush(fd)
    }

    actual fun close() {
        fclose(fd)
    }
}
