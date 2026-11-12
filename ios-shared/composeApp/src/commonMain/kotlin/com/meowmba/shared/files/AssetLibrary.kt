package com.meowmba.shared.files

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.openWrite
import com.meowmba.shared.agent.relativeTo
import com.meowmba.shared.agent.use
import com.meowmba.shared.bridge.BridgeCrypto
import com.meowmba.shared.core.PlatformId
import com.meowmba.shared.core.PlatformLock
import com.meowmba.shared.core.PlatformTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class AssetEntry(val path: String, val name: String, val folder: Boolean, val bytes: Long, val modified: Long, val category: String)
data class AssetScan(val entries: List<AssetEntry>, val truncated: Boolean)
data class AssetMove(val source: AssetEntry, val destination: String)
data class CleanupCandidate(val asset: AssetEntry, val reason: String)
data class TrashEntry(val id: String, val original: String, val bytes: Long, val date: Long)

/**
 * The browser and agents share the existing workspace; never scan keys or model
 * storage.
 *
 * Ported from `core/files/AssetLibrary.kt`. Pure Kotlin on top of the module's
 * `ModelFile` seam — no `expect`/`actual` was added for it.
 *
 * ## Translations
 *
 * | original | port |
 * |---|---|
 * | `java.io.File` | `ModelFile` |
 * | `Files.isSymbolicLink(f.toPath())` | `f.isSymbolicLink()` |
 * | `@Synchronized fun` | `lock.withLock { }` (no `…Locked` split was needed — none of these has an early `return`) |
 * | `UUID.randomUUID().toString().take(8)` | `PlatformId.shortId()` — both 8 lowercase hex chars |
 * | `UUID.randomUUID().toString()` | `PlatformId.uuid()` — see [trashDir] for why the full shape matters |
 * | `MessageDigest` + `"%02x".format` | `BridgeCrypto.sha256Hex` (same lowercase hex) |
 * | `System.currentTimeMillis()` | `PlatformTime.currentMillis()` |
 * | `Properties.store`/`load` in `entry.properties` | a two-key JSON object in `entry.json` |
 * | `File.createNewFile()` | check-then-create under the lock, see [newOutput] |
 * | `MutableMap.putIfAbsent` | a null read then a write — `putIfAbsent` is `java.util.Map`'s, and the iOS compiles reject it while `compileKotlinJvm` accepts it |
 *
 * ## Two control-character sets, and they are not the same set
 *
 * The original used `Char.isISOControl()` in [resolve] and the regex `\p{Cntrl}`
 * in [safeName]. Those look like the same predicate and are not:
 * `Character.isISOControl` covers C0, DEL **and the C1 block** (U+007F–U+009F),
 * while Java's `\p{Cntrl}` is exactly `[\x00-\x1F\x7F]`. Both were confirmed
 * against the JDK rather than recalled. [isIsoControl] and [isCntrlClass] keep
 * them apart, and `AssetLibraryTest` pins the difference with U+0085, which one
 * strips and the other keeps.
 *
 * The regex itself is gone: `\p{…}` support in Kotlin/Native's `Regex` is not
 * something to bet a security check on, so the class is spelled out as the
 * character set it was verified to be.
 *
 * ## Deferred
 *
 * `import(name, folder, input)` is **not ported**. It takes a
 * `java.io.InputStream`, which has no common equivalent — the whole point of the
 * function is to stream from a platform source (an Android `content://` picker
 * result) with a running size total, an 11 GB cap, a free-space check and a
 * thread-interrupt check. Every one of those four things is either a JVM-only API
 * (`InputStream`, `Thread.currentThread().isInterrupted`, `File.usableSpace`) or
 * meaningless without the stream (a byte-array signature makes the 11 GB cap
 * unreachable and would be a guard that no longer guards). It needs a platform
 * *stream source* type, which is the same prerequisite as the Files importer that
 * would call it. `newOutput` — the part `Workspace.newAsset` actually needs — is
 * ported, and its never-overwrite property is pinned directly instead of through
 * `import`.
 */
class AssetLibrary(val root: ModelFile) {
    companion object {
        const val TRASH = ".asset-trash"
        val folders = listOf("projects", "photos", "videos", "audio", "documents", "imports")
        fun category(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "heic", "avif", "svg" -> "Photos"
            "mp4", "mkv", "webm", "mov", "m4v", "avi" -> "Videos"
            "wav", "mp3", "ogg", "m4a", "flac", "aac" -> "Audio"
            "zip", "tar", "gz", "bz2", "7z" -> "Archives"
            else -> "Documents"
        }
        fun safeName(value: String): String = value.map { c ->
            if (isCntrlClass(c) || c in UNSAFE_NAME_CHARS) '_' else c
        }.joinToString("").trim().trim('.').take(160).ifBlank { "asset" }

        /** `Character.isISOControl`: C0, DEL **and C1**. */
        private fun isIsoControl(c: Char): Boolean = c.code <= 0x1F || c.code in 0x7F..0x9F

        /** Java's `\p{Cntrl}`: C0 and DEL only. Deliberately narrower than [isIsoControl]. */
        private fun isCntrlClass(c: Char): Boolean = c.code <= 0x1F || c.code == 0x7F

        private val UNSAFE_NAME_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    }

    private val lock = PlatformLock()

    init { check(root.mkdirs() || root.isDirectory()) { "Asset library is unavailable" } }

    fun resolve(path: String, allowRoot: Boolean = false): ModelFile {
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
            path.none { isIsoControl(it) } &&
            path.split('/').none { it == ".." || it.startsWith('.') && it.isNotEmpty() }) { "Unsafe asset path" }
        var segment = root
        path.split('/').filter { it.isNotEmpty() }.forEach { part ->
            segment = ModelFile("${segment.path}/$part")
            require(!segment.isSymbolicLink()) { "Linked paths are not supported" }
        }
        val canonicalRoot = ModelFile(root.canonicalPath())
        val f = ModelFile(ModelFile("${root.path}/$path").canonicalPath())
        require((allowRoot && f.path == canonicalRoot.path) ||
            f.path.startsWith(canonicalRoot.path + "/")) { "Path is outside the asset library" }
        require(allowRoot || path.isNotBlank()) { "Choose a file" }
        return f
    }

    fun ensureFolders() { folders.forEach { check(resolve(it).mkdirs() || resolve(it).isDirectory()) } }

    fun entry(file: ModelFile): AssetEntry {
        // resolve() returns a canonical file; a platform root alias (macOS
        // /tmp -> /private/tmp, or Android /data/data) must not become ../...
        // when an imported/generated file is handed back to the browser.
        // Keep the leaf path lexical so resolve() still rejects inner symlinks.
        //
        // `File.absolutePath` becomes `path`: ModelFile has no notion of a
        // working directory, and every caller here passes an absolute root.
        val canonicalRoot = root.canonicalPath()
        val base = if (file.path.startsWith("$canonicalRoot/")) ModelFile(canonicalRoot) else root
        val path = file.relativeTo(base)
        resolve(path)
        return AssetEntry(path, file.name, file.isDirectory(), if (file.isFile()) file.length() else 0L, file.lastModified(),
            if (file.isDirectory()) "Folders" else category(file.name))
    }

    /** Bounded, symlink-free enumeration. A truncated scan is always reported to the UI. */
    fun scan(limit: Int = 5000): AssetScan {
        val entries = mutableListOf<AssetEntry>()
        val pending = ArrayDeque<Pair<ModelFile, Int>>()
        pending.addLast(root to 0)
        var truncated = false
        while (pending.isNotEmpty() && entries.size < limit) {
            val (dir, depth) = pending.removeFirst()
            // The original's `dir.listFiles() ?: continue`: skip anything that is
            // not a readable directory. `ModelFile.listFiles` reports that as an
            // empty list rather than null, so the test is explicit here.
            if (!dir.isDirectory()) continue
            for (file in dir.listFiles().sortedBy { it.name.lowercase() }) {
                if (file.name.startsWith('.') || file.isSymbolicLink()) continue
                if (entries.size >= limit) { truncated = true; break }
                val item = runCatching { entry(file) }.getOrNull() ?: continue
                entries += item
                if (file.isDirectory()) {
                    if (depth < 10) pending.addLast(file to depth + 1) else truncated = true
                }
            }
        }
        return AssetScan(entries, truncated || pending.isNotEmpty())
    }

    fun search(query: String): AssetScan {
        val scan = scan()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return scan.copy(entries = scan.entries.filter { item -> terms.all { it in "${item.path} ${item.category}".lowercase() } })
    }

    fun createFolder(path: String): ModelFile = lock.withLock {
        resolve(path).also {
            require(!it.exists()) { "That folder already exists" }
            check(it.mkdirs()) { "Could not create folder" }
        }
    }

    /**
     * A new destination every time: imports and generated outputs must not
     * overwrite.
     *
     * `File.createNewFile()` is atomic *across processes*; the port checks then
     * creates under the library lock, which is the same in-process guarantee the
     * original's `@Synchronized` gave and no weaker, since the only other thing
     * that could collide is the 8-hex-char random prefix. Cross-process
     * reservation would need a new seam member, and there is no second process
     * that writes this library.
     */
    fun newOutput(folder: String, name: String): ModelFile = lock.withLock {
        val dir = resolve(folder)
        check(dir.mkdirs() || dir.isDirectory())
        val safe = safeName(name)
        val destination = resolve("$folder/${PlatformId.shortId()}_$safe")
        // `check`, not `require`: the original's failure path is
        // `check(destination.createNewFile())`, i.e. IllegalStateException.
        check(!destination.exists()) { "Could not reserve output" }
        destination.openWrite().use { }
        destination
    }

    private fun unchanged(item: AssetEntry): ModelFile = resolve(item.path).also {
        require(it.isFile() && !item.folder && it.length() == item.bytes && it.lastModified() == item.modified) { "${item.name} changed. Refresh and review it again." }
    }

    /** Each move is independently checked; no overwrites, root moves or invented AI paths. */
    fun move(plan: AssetMove) = lock.withLock {
        val source = unchanged(plan.source)
        val target = resolve(plan.destination)
        // Compare paths, not instances: `java.io.File.equals` is path-based but
        // `ModelFile` does not override `equals`, so the original's
        // `source != target` would be reference identity here — always true, and
        // therefore never the guard it looks like. Third instance of this trap in
        // the port (`Workspace.list`, `WorkflowTools`' walk, and here).
        require(source.path != target.path && !target.exists()) { "Destination already exists or is unchanged" }
        check(target.parent()!!.mkdirs() || target.parent()!!.isDirectory())
        check(source.renameTo(target)) { "Could not move ${plan.source.name}" }
    }

    fun cleanup(): Pair<List<CleanupCandidate>, Boolean> {
        val scan = scan()
        val files = scan.entries.filter { !it.folder }
        val candidates = linkedMapOf<String, CleanupCandidate>()
        files.filter { it.bytes == 0L }.forEach { candidates[it.path] = CleanupCandidate(it, "Empty file — review before removing") }
        var remaining = 1_000_000_000L
        var limited = scan.truncated
        for (group in files.filter { it.bytes > 0 }.groupBy { it.bytes }.values.filter { it.size > 1 }) {
            val hashes = mutableMapOf<String, AssetEntry>()
            for (item in group.sortedBy { it.path }) {
                if (item.bytes > remaining) { limited = true; continue }
                remaining -= item.bytes
                val file = runCatching { unchanged(item) }.getOrNull() ?: continue
                // The original streamed the file through a MessageDigest in 64 KB
                // chunks. `BridgeCrypto` takes a whole buffer, so this reads the
                // file in one go. The 1 GB hashing budget below still bounds the
                // total work, but not the peak allocation, so this is the one
                // place a very large duplicate would cost real memory. Worth a
                // streaming digest seam if a library ever holds files that big.
                val hash = file.readBytes()?.let { BridgeCrypto.sha256Hex(it) } ?: continue
                // Not `hashes.putIfAbsent(hash, item)`: that resolves to
                // `java.util.Map.putIfAbsent`, which is a JVM member with no
                // Kotlin/Native equivalent. `compileKotlinJvm` accepts it and
                // the iOS compiles reject it, which is what the portability
                // gate is for. `AssetEntry` is never stored as null, so a null
                // read is exactly "absent".
                val keeper = hashes[hash]
                if (keeper == null) hashes[hash] = item
                else candidates[item.path] = CleanupCandidate(item, "Identical bytes at scan time to ${keeper.path}")
            }
        }
        files.filter { it.bytes >= 100_000_000L && it.path !in candidates }.forEach {
            candidates[it.path] = CleanupCandidate(it, "Large file — not necessarily unwanted")
        }
        return candidates.values.toList() to limited
    }

    private fun trashDir(id: String): ModelFile {
        // The full UUID shape is the guard: anything with a '/' or a '.' — i.e.
        // every traversal attempt — fails here before the id is used as a
        // directory name. `PlatformId.uuid()` exists so this regex did not have to
        // be widened.
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid trash item" }
        val base = ModelFile("${root.path}/$TRASH")
        require(!base.isSymbolicLink())
        return ModelFile("${base.path}/$id").also {
            require(it.canonicalPath().startsWith("${root.canonicalPath()}/$TRASH/"))
        }
    }

    private fun metadataFile(dir: ModelFile) = ModelFile("${dir.path}/entry.json")

    private fun readMetadata(dir: ModelFile): Pair<String, Long>? {
        val text = metadataFile(dir).readText() ?: return null
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            obj.getValue("path").jsonPrimitive.content to obj.getValue("date").jsonPrimitive.long
        }.getOrNull()
    }

    fun trash(item: AssetEntry): String = lock.withLock {
        val source = unchanged(item)
        val id = PlatformId.uuid()
        val dir = trashDir(id)
        check(dir.mkdirs())
        // Properties.store wrote `path` and `date` into `entry.properties`; the
        // file is private to this class and nothing else reads it, so a two-key
        // JSON object replaces the hand-rolled key=value format the port would
        // otherwise have to invent and test.
        metadataFile(dir).writeBytesAtomic(
            buildJsonObject {
                put("path", item.path)
                put("date", PlatformTime.currentMillis())
            }.toString().encodeToByteArray(),
        )
        try {
            check(source.renameTo(ModelFile("${dir.path}/payload"))) { "Could not move file to Trash" }
        } catch (e: Exception) {
            metadataFile(dir).delete()
            dir.delete()
            throw e
        }
        id
    }

    fun trashItems(): List<TrashEntry> = ModelFile("${root.path}/$TRASH").listFiles().mapNotNull { folder ->
        runCatching {
            val dir = trashDir(folder.name)
            val payload = ModelFile("${dir.path}/payload")
            require(payload.isFile() && !payload.isSymbolicLink())
            val (path, date) = readMetadata(dir) ?: error("Trash entry is missing its restore location")
            resolve(path)
            TrashEntry(folder.name, path, payload.length(), date)
        }.getOrNull()
    }.sortedByDescending { it.date }

    fun restore(id: String) = lock.withLock {
        val item = trashItems().firstOrNull { it.id == id } ?: error("Trash item is missing")
        val target = resolve(item.original)
        require(!target.exists()) { "A file already exists at ${item.original}; move it first" }
        check(target.parent()!!.mkdirs() || target.parent()!!.isDirectory())
        val dir = trashDir(id)
        check(ModelFile("${dir.path}/payload").renameTo(target))
        metadataFile(dir).delete(); dir.delete()
    }

    /** Only explicit per-item permanent-delete confirmation may call this. */
    fun purge(id: String) = lock.withLock {
        require(trashItems().any { it.id == id }) { "Trash item is missing" }
        val dir = trashDir(id)
        check(ModelFile("${dir.path}/payload").delete()) { "Could not delete file" }
        metadataFile(dir).delete(); dir.delete()
    }
}
