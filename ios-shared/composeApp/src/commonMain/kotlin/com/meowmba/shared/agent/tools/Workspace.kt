package com.meowmba.shared.agent.tools

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.openWrite
import com.meowmba.shared.agent.relativeTo
import com.meowmba.shared.agent.use
import com.meowmba.shared.agent.walkTopDown
import com.meowmba.shared.files.AssetLibrary
import com.meowmba.shared.files.AssetScan
import com.meowmba.shared.work.ProjectExecutionContext

/**
 * The agent workspace: a sandboxed directory that is the ONLY place file and
 * project tools may touch. Path resolution rejects anything that escapes the
 * root (`../`, absolute paths, symlink tricks), which is what makes the file
 * tools safe to expose to agents at all.
 *
 * Ported from `core/agent/tools/Workspace.kt`. The escape check is the whole
 * reason this class exists, so it is translated exactly: the path is made
 * root-relative by trimming leading separators, and the result is accepted only
 * when its **canonical** form is the canonical root or sits under it. Comparing
 * raw strings would pass `root/../outside` and `root/link` (a symlink pointing
 * out), which are both textually "under root".
 *
 * ## Why the project-aware half is `suspend`
 *
 * `newAsset`, `resolveRead`, `resolveWrite`, `scanFiles`, and therefore `read`
 * and `write`, are `suspend` here where the original was not. That is not
 * gratuitous: all of them consult the active project folder, which comes from
 * `ProjectExecutionContext.folder()`, and in the port that value lives in the
 * coroutine context rather than in a `ThreadLocal` — the original's storage is
 * JVM-only. See [ProjectExecutionContext] for the full argument. In the original
 * every caller of these methods is already inside a `suspend` tool `execute`,
 * so nothing is made harder to call; the `ThreadLocal` was what let the
 * signatures *look* synchronous.
 *
 * `resolve`, `delete` and `list` stay non-suspending: they never consult the
 * project scope.
 *
 * ## Translations
 *
 * | original | port |
 * |---|---|
 * | `File.isFile` / `isDirectory` | `isFile()` / `isDirectory()` |
 * | `file.parentFile?.mkdirs()` | `parent()?.mkdirs()` |
 * | `file.writeText(content)` | `openWrite().use { … }` |
 * | `it.relativeTo(root).path` | `relativeTo(root)` |
 * | `AssetLibrary(root)` | unchanged (already ported, step 12) |
 *
 * `AssetLibrary.resolve` **throws** on an unsafe path or one outside the library,
 * where the original's `File`-based helper returned null. That is why
 * [resolveRead] wraps its probe in `runCatching`: the probe is speculative — it
 * asks "is there a project copy?" — and a rejected path there is an ordinary
 * miss, not an error. The original needed the same `runCatching` for the same
 * reason, so the shape is unchanged.
 */
class Workspace(val root: ModelFile, private val activeProjectFolder: () -> String? = { null }) {

    init {
        root.mkdirs()
    }

    /** Shared with Files: unique outputs, grouped under the active project when present. */
    suspend fun newAsset(folder: String, name: String): ModelFile =
        AssetLibrary(root).newOutput(projectFolder()?.let { "$it/$folder" } ?: folder, name)

    private suspend fun projectFolder(): String? = ProjectExecutionContext.folder(activeProjectFolder)

    /**
     * Relative reads must round-trip project-scoped writes. A full `projects/…`
     * path or leading `/` explicitly addresses the workspace root, including a
     * reference in another project. Otherwise prefer an existing project file,
     * then retain access to existing shared library references at their paths.
     */
    suspend fun resolveRead(relativePath: String): ModelFile? {
        val path = relativePath.trim()
        val folder = projectFolder()
        if (folder != null && path.isNotBlank() && !path.startsWith('/') &&
            !path.startsWith("projects/") && !path.contains('\\') && path.split('/').none { it == ".." }
        ) {
            val local = runCatching { AssetLibrary(root).resolve("$folder/$path") }.getOrNull()
            if (local?.isFile() == true) return local
        }
        return resolve(path)
    }

    /** Preserve the Files browser's bounds and hidden/symlink exclusions. */
    suspend fun scanFiles(limit: Int = 5000): AssetScan {
        val library = AssetLibrary(root)
        val folder = projectFolder() ?: return library.scan(limit)
        val dir = library.resolve(folder)
        if (!dir.isDirectory()) return AssetScan(emptyList(), false)
        val scan = AssetLibrary(dir).scan(limit)
        // The paths are re-prefixed with the folder so a project-scoped scan is
        // still addressable through this workspace's own root-relative names.
        return scan.copy(entries = scan.entries.map { it.copy(path = "$folder/${it.path}") })
    }

    /** New text outputs live with the captured project; explicit other project paths are rejected. */
    suspend fun resolveWrite(relativePath: String): ModelFile? {
        val folder = projectFolder() ?: return resolve(relativePath)
        val path = relativePath.trim()
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it == ".." }
        ) {
            return null
        }
        // A path that already names a project may only name *this* project.
        if (path.startsWith("projects/") && !path.startsWith("$folder/")) return null
        return resolve(if (path.startsWith("$folder/")) path else "$folder/$path")
    }

    /**
     * Resolve a user/agent-supplied relative path against the sandbox root.
     * Returns null when the path tries to escape.
     *
     * Leading `/` and `\` are **trimmed, not rejected**: the original treats an
     * absolute-looking path as root-relative, so `/etc/passwd` addresses
     * `<root>/etc/passwd`. That is safe (the containment check still applies)
     * and it matches the original, so it is preserved rather than "hardened".
     */
    fun resolve(relativePath: String): ModelFile? {
        val clean = relativePath.trim().trimStart('/', '\\')
        if (clean.isBlank()) return null
        val resolved = ModelFile("${root.path.trimEnd('/')}/$clean")
        return try {
            val canonicalRoot = root.canonicalPath()
            val canonicalResolved = resolved.canonicalPath()
            if (canonicalResolved == canonicalRoot ||
                canonicalResolved.startsWith("$canonicalRoot/")
            ) {
                resolved
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Read a text file. Null = not found / not a file / escape attempt. */
    suspend fun read(relativePath: String): String? {
        val file = resolveRead(relativePath) ?: return null
        // ModelFile.readText already refuses a non-file and swallows read errors,
        // so this is the original's `if (file.isFile) runCatching { … } else null`.
        return file.readText()
    }

    /**
     * Write a text file (creates parent directories). False = escape/failure.
     *
     * Truncates in place, like the original's `writeText`. Durability is
     * deliberately *not* promised here — callers that need crash-safety (the
     * report writer, the draft outbox) use [ModelFile.writeBytesAtomic]
     * directly, as the original did.
     */
    suspend fun write(relativePath: String, content: String): Boolean {
        val file = resolveWrite(relativePath) ?: return false
        return runCatching {
            file.parent()?.mkdirs()
            val bytes = content.encodeToByteArray()
            file.openWrite().use { it.write(bytes, 0, bytes.size) }
            true
        }.getOrDefault(false)
    }

    /**
     * Delete a file or empty directory inside the sandbox.
     *
     * **The emptiness check is not redundant, despite being invisible on the
     * JVM.** `java.io.File.delete()` already refuses a non-empty directory, so
     * deleting this guard changes nothing on JVM/Android and no test on this
     * machine can detect it — verified: the mutation survives. On iOS it is the
     * only thing standing between a model and `rm -rf`:
     * `NSFileManager.removeItemAtPath` removes a directory **and everything
     * under it**, so without this line `files.delete("projects")` would silently
     * destroy a project tree. Do not "simplify" this into the platform call.
     */
    fun delete(relativePath: String): Boolean {
        val file = resolve(relativePath) ?: return false
        if (!file.exists()) return false
        // Guard against recursive nuking: directories only if empty.
        if (file.isDirectory() && file.listFiles().isNotEmpty()) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** List files under a sandbox subdirectory ("" = root), one path per line. */
    fun list(relativePath: String = ""): List<String> {
        val dir = if (relativePath.isBlank()) root else resolve(relativePath) ?: return emptyList()
        if (!dir.isDirectory()) return emptyList()
        return dir.walkTopDown()
            // Compare paths, not instances. The original wrote `it != dir`, which
            // worked because `java.io.File.equals` is path-based; `ModelFile` does
            // not override equals, so the same expression here would be reference
            // identity — correct today only because the receiver is yielded
            // first, and silently wrong the moment the walk changes.
            .filter { it.path != dir.path }
            .map { it.relativeTo(root) + if (it.isDirectory()) "/" else "" }
            .sorted()
            .toList()
    }
}
