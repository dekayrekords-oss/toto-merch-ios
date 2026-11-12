package com.meowmba.shared.agent

/**
 * Minimal platform file abstraction (`java.io.File` is JVM-only). Actuals:
 *  - JVM/Android -> java.io.File + FileOutputStream
 *  - iOS -> NSFileManager + POSIX stdio
 *
 * Started as the model-download layer's file type and grew into the module's
 * one general file seam, because a second parallel abstraction would mean two
 * `expect`/`actual` triples to keep in step. Everything that needs to touch
 * real paths goes through this.
 */
expect class ModelFile(path: String) {
    val path: String
    val name: String
    fun exists(): Boolean
    fun isFile(): Boolean
    fun isDirectory(): Boolean
    fun length(): Long
    fun lastModified(): Long
    fun delete(): Boolean
    fun mkdirs(): Boolean
    fun renameTo(target: ModelFile): Boolean
    fun parent(): ModelFile?
    /** Direct children (files and directories); empty when not a directory. */
    fun listFiles(): List<ModelFile>

    /**
     * Fully-resolved absolute path, with `.`, `..` and **symlinks** collapsed.
     *
     * This is the sandbox's containment check: `Workspace.resolve` accepts a
     * path only when its canonical form is inside the canonical root, which is
     * what makes `../outside` and a symlink pointing outside both fail. Compare
     * canonical forms, never raw ones — `root/../outside` and `root/link` are
     * both textually "under root".
     *
     * Matches `java.io.File.canonicalPath`, which resolves as much of the path
     * as exists and collapses the remainder lexically, so a path to a file that
     * has not been created yet still canonicalises. Returns [path] unchanged
     * when nothing at all can be resolved.
     */
    fun canonicalPath(): String

    /**
     * True when this path is itself a symbolic link. Does not follow the link,
     * so it answers "is this entry a link", not "does this lead outside".
     */
    fun isSymbolicLink(): Boolean

    /** Whole file as UTF-8 text, or null when missing, not a file, or unreadable. */
    fun readText(): String?

    /** Whole file as bytes, or null when missing, not a file, or unreadable. */
    fun readBytes(): ByteArray?

    /**
     * Writes [bytes] durably: stage a sibling temp file in the same directory,
     * flush it to the storage device, then rename it over this path.
     *
     * The plain [ModelFileSink] truncates the target in place, so a crash or a
     * full disk mid-write leaves a *partial* file where the previous good one
     * used to be. That is unacceptable for a business report or a review draft,
     * which is what this is for. On a POSIX filesystem `rename` within one
     * directory is atomic, so a reader sees either the old contents or the new,
     * never a half-written file.
     *
     * Replaces any existing file at this path. Creates parent directories.
     * Costs a second file and an fsync, so prefer the sink for anything
     * reconstructible.
     */
    fun writeBytesAtomic(bytes: ByteArray)
}

/** Byte sink produced by [ModelFile.openWrite]. Caller must [close]. */
expect class ModelFileSink(file: ModelFile) {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun flush()
    fun close()
}

fun ModelFile.openWrite(): ModelFileSink = ModelFileSink(this)

inline fun ModelFileSink.use(block: (ModelFileSink) -> Unit) {
    try {
        block(this)
    } finally {
        close()
    }
}

/**
 * Delete this file, or this directory and everything under it.
 *
 * Built in common code on top of [ModelFile.listFiles] rather than as another
 * `expect`/`actual`: the walk is identical on every platform, and a recursive
 * delete has enough ways to go wrong that one implementation is better than
 * three. Symlinks are treated as leaves — [ModelFile.isDirectory] reports false
 * for them on both JVM and iOS — so this cannot escape the tree it was pointed
 * at.
 *
 * Returns true when nothing remains at this path.
 */
fun ModelFile.deleteRecursively(): Boolean {
    if (isDirectory()) {
        listFiles().forEach { it.deleteRecursively() }
    }
    delete()
    return !exists()
}
