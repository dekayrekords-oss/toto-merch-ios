package com.meowmba.shared.agent

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

actual class ModelFile actual constructor(path: String) {
    private val file = File(path)

    actual val path: String get() = file.path
    actual val name: String get() = file.name
    actual fun exists(): Boolean = file.exists()
    actual fun isFile(): Boolean = file.isFile
    actual fun isDirectory(): Boolean = file.isDirectory
    actual fun length(): Long = file.length()
    actual fun lastModified(): Long = file.lastModified()
    actual fun delete(): Boolean = file.delete()
    actual fun mkdirs(): Boolean = file.mkdirs()
    actual fun renameTo(target: ModelFile): Boolean = file.renameTo(File(target.path))
    actual fun parent(): ModelFile? = file.parentFile?.let { ModelFile(it.path) }
    actual fun listFiles(): List<ModelFile> =
        file.listFiles()?.map { ModelFile(it.path) } ?: emptyList()

    /**
     * `canonicalPath` is exactly the semantics the sandbox wants, including
     * resolving as much of a not-yet-existing path as it can. It can throw
     * IOException in odd cases; falling back to the raw path keeps the result
     * non-null and is fail-closed, because a raw path is not a canonical root
     * prefix and `Workspace.resolve` then refuses it.
     */
    actual fun canonicalPath(): String = runCatching { file.canonicalPath }.getOrDefault(file.path)

    actual fun isSymbolicLink(): Boolean = Files.isSymbolicLink(file.toPath())

    actual fun readBytes(): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    actual fun readText(): String? =
        if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    actual fun writeBytesAtomic(bytes: ByteArray) {
        // A sibling temp file is required, not just tidy: rename is only atomic
        // within one directory (one filesystem), so a temp elsewhere could be a
        // cross-device copy that is neither atomic nor a rename.
        val parent = file.parentFile ?: error("No parent directory for ${file.path}")
        parent.mkdirs()
        val pending = Files.createTempFile(parent.toPath(), ".pending-", ".tmp")
        try {
            FileOutputStream(pending.toFile()).use { out ->
                out.write(bytes)
                // flush() only pushes to the OS; fd.sync() pushes to the device.
                out.fd.sync()
            }
            Files.move(
                pending,
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}

actual class ModelFileSink actual constructor(file: ModelFile) {
    private val out = FileOutputStream(file.path)

    actual fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
    }

    actual fun flush() = out.flush()
    actual fun close() = out.close()
}
