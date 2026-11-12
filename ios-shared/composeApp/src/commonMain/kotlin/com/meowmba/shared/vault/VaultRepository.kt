package com.meowmba.shared.vault

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.AppStorage
import com.meowmba.shared.core.PlatformLock
import com.meowmba.shared.core.PlatformTime
import com.meowmba.shared.memory.DateText

/**
 * Obsidian-compatible vault, ported from Android `core/vault/VaultRepository`: plain Markdown files,
 * one per note, in a sandboxed directory. File names are the sanitized title; front-matter (`---`)
 * carries tags and timestamps. `java.io.File` → [ModelFile], `@Synchronized` → [PlatformLock].
 */
class VaultRepository(val dir: ModelFile = ModelFile(AppStorage.location().trimEnd('/') + "/vault")) {

    private val lock = PlatformLock()

    init { dir.mkdirs() }

    /** All notes, newest first. Includes one level of subfolders (Notes/). */
    fun list(): List<VaultNote> = lock.withLock {
        markdownFiles(dir, depth = 2).mapNotNull { parse(it) }.sortedByDescending { it.createdAt }
    }

    /** A single note by title. Null = not found. */
    fun get(title: String): VaultNote? = lock.withLock {
        val file = file(title)
        if (file.isFile()) parse(file) else null
    }

    /** Create or overwrite a note; returns the saved note. */
    fun save(title: String, body: String, tags: List<String> = emptyList()): VaultNote = lock.withLock {
        val target = file(title)
        val existing = if (target.isFile()) parse(target) else null
        val now = PlatformTime.currentMillis()
        val note = VaultNote(title.trim(), body, tags, existing?.createdAt ?: now, now)
        target.writeBytesAtomic(serialize(note).encodeToByteArray())
        note
    }

    /** Append text to an existing note (or create it). */
    fun append(title: String, text: String): VaultNote {
        val existing = get(title)
        val body = if (existing != null) existing.body + "\n\n" + text.trim() else text.trim()
        return save(title, body, existing?.tags ?: emptyList())
    }

    fun delete(title: String): Boolean = lock.withLock { file(title).delete() }

    /** Title matches first, then body matches (case-insensitive). */
    fun search(query: String): List<VaultNote> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val all = list()
        return all.filter { q in it.title.lowercase() } +
            all.filter { q !in it.title.lowercase() && q in it.body.lowercase() }
    }

    /** [[wikilinks]] in a note that do not have their own file yet. */
    fun missingLinks(note: VaultNote): List<String> =
        WIKILINK.findAll(note.body).map { it.groupValues[1].trim() }.filter { get(it) == null }.toList()

    private fun markdownFiles(folder: ModelFile, depth: Int): List<ModelFile> = folder.listFiles().flatMap {
        when {
            it.isFile() && it.name.endsWith(".md", ignoreCase = true) -> listOf(it)
            it.isDirectory() && depth > 1 -> markdownFiles(it, depth - 1)
            else -> emptyList()
        }
    }

    private fun serialize(note: VaultNote): String = buildString {
        val offset = PlatformTime.utcOffsetMinutes()
        append("---\n")
        append("title: ${note.title}\n")
        append("created: ${DateText.localStamp(note.createdAt, offset)}\n")
        append("modified: ${DateText.localStamp(note.modifiedAt, offset)}\n")
        if (note.tags.isNotEmpty()) append("tags: [${note.tags.joinToString(", ")}]\n")
        append("---\n\n")
        append(note.body)
    }

    private fun parse(file: ModelFile): VaultNote? = runCatching {
        val text = file.readText() ?: return null
        val fallbackTitle = file.name.removeSuffix(".md").removeSuffix(".MD").replace('_', ' ')
        if (!text.startsWith("---")) {
            return VaultNote(fallbackTitle, text, emptyList(), file.lastModified(), file.lastModified())
        }
        val end = text.indexOf("\n---", 4)
        if (end < 0) return null
        val front = text.substring(4, end)
        val body = text.substring(end + 5).trimStart('\n')
        var title = fallbackTitle
        var createdAt = file.lastModified()
        var modifiedAt = file.lastModified()
        val tags = mutableListOf<String>()
        val offset = PlatformTime.utcOffsetMinutes()
        for (line in front.lines()) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val value = line.substring(colon + 1).trim()
            when (line.substring(0, colon).trim()) {
                "title" -> title = value
                "created" -> DateText.parseLocalStamp(value, offset)?.let { createdAt = it }
                "modified" -> DateText.parseLocalStamp(value, offset)?.let { modifiedAt = it }
                "tags" -> tags += value.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        }
        VaultNote(title, body, tags, createdAt, modifiedAt)
    }.getOrNull()

    private fun file(title: String): ModelFile {
        val relative = title.split('/').map(::sanitize).filter { it.isNotBlank() }.joinToString("/") + ".md"
        return ModelFile(dir.path.trimEnd('/') + "/" + relative).also { it.parent()?.mkdirs() }
    }

    private fun sanitize(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9 _\\-]"), "_").replace(' ', '_').take(120)

    companion object {
        private val WIKILINK = Regex("\\[\\[([^\\]]+)\\]\\]")
    }
}

data class VaultNote(
    val title: String,
    val body: String,
    val tags: List<String>,
    val createdAt: Long,
    val modifiedAt: Long,
) {
    /** Plain text preview for list items. */
    val preview: String get() = body.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: ""
}
