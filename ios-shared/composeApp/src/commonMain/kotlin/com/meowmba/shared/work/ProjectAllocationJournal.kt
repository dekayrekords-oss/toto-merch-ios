package com.meowmba.shared.work

import com.meowmba.shared.agent.ModelFile
import kotlinx.serialization.json.*

/**
 * Written BEFORE Room insertion. An interrupted insert can be reconciled by
 * its reserved conversation identity without allocating another project.
 *
 * Ported from `core/work/ProjectAllocationJournal.kt`. It is a
 * write-ahead log, so almost all of its body is guards: eleven of them (nine
 * `check`s and two `checkNotNull`s) standing between a half-written JSON file
 * and a duplicated project. Every one is carried over, and the substitutions
 * are all in the file seam:
 *
 * | original                              | here                       |
 * |---------------------------------------|----------------------------|
 * | `java.io.File`                        | [ModelFile]                |
 * | `Files.isSymbolicLink(file.toPath())` | [ModelFile.isSymbolicLink] |
 * | `file.parentFile`                     | [ModelFile.parent]         |
 * | `file.isFile`, `file.length()`        | same                       |
 * | `file.readText()` (throws)            | returns `String?`          |
 * | `Files.createTempFile` + `FileOutputStream` + `fd.sync()` + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` | [ModelFile.writeBytesAtomic] |
 * | `Json.encodeToString(obj).toByteArray(Charsets.UTF_8)` | `buildJsonObject { ... }.toString().encodeToByteArray()` |
 *
 * [ModelFile.writeBytesAtomic] is not an approximation of the original's
 * last five lines — it *is* that sequence, stage a sibling temp file, fsync it,
 * rename it over the target, and delete the temp in a `finally`. So the
 * journal keeps its one non-negotiable property: the file it reads back is
 * either the whole previous version or the whole next one, never a prefix.
 * The original's comment on that point is the reason the fallback is absent,
 * and it is worth restating here — a plain truncating write is *not* an
 * acceptable degradation, because losing a receipt lets the next launch
 * allocate a second project for a conversation that already has one.
 *
 * `ProjectKind` — the only cross-file dependency in `read` — was ported in the
 * request-interpretation step, so this file needed no new neighbour.
 *
 * ### The original ships this class with no tests
 *
 * Eleven guards, and upstream `core/work` has no test source set at all:
 * nothing in the repository constructs this class. Every test in
 * `ProjectAllocationJournalTest` is therefore port-only, written from the
 * contract the guards encode rather than from an existing suite. That is the
 * first time in this port that a file arrived with *zero* upstream coverage,
 * and it is worth being explicit about the risk it carries: a guard can be
 * deleted without any upstream test objecting, so the mutation pass for this
 * file is the only thing standing behind eleven checks.
 *
 * ### One guard is redundant *here*, and was not in the original
 *
 * Mutation testing found that deleting the `isFile()` clause of the first
 * `check` changes nothing observable: a directory at the journal path then
 * falls through to `readText()`, which returns null, and the `checkNotNull`
 * below throws the *same* `IllegalStateException` with the *same* message. The
 * two guards overlap on exactly one input class, "a directory where the journal
 * should be", and agree on what to say about it.
 *
 * In the original the clause is not redundant: `java.io.File.readText()` on a
 * directory throws `FileNotFoundException`, so without `isFile()` the user would
 * see a raw `IOException` instead of the "Review Projects before creating more"
 * message. Mapping a throwing `readText()` onto a nullable one collapsed that
 * distinction. The redundancy is harmless — both paths give the same type and
 * the same user-facing text — and both guards still do work: `isFile()` is the
 * one that fires in practice, and the null check is the last resort for a file
 * that exists but cannot be opened. It is left as-is rather than collapsed,
 * because inventing a second message to make the two separately observable
 * would be a product decision, not a porting one.
 */
internal class ProjectAllocationJournal(private val file: ModelFile) {
    data class Receipt(val key: String, val name: String, val kind: String, val at: Long,
        val conversationId: Long, val projectId: Long = 0, val verified: Boolean = false)

    fun read(): List<Receipt> {
        check(!file.isSymbolicLink()) { "Linked project allocation storage is not supported" }
        if (!file.exists()) return emptyList()
        check(file.isFile() && file.length() <= 64_000) { "Project allocation history is unavailable. Review Projects before creating more." }
        // `java.io.File.readText()` throws where this returns null, and the two
        // `check`s above are what the original relies on to make the throw
        // unreachable. The null is the same condition arriving as a value, so it
        // reports the same thing to the user: the history cannot be read.
        val text = checkNotNull(file.readText()) { "Project allocation history is unavailable. Review Projects before creating more." }
        val root = Json.parseToJsonElement(text).jsonObject
        check(root["version"]?.jsonPrimitive?.int == 1) { "Unknown project allocation journal version" }
        val records = root.getValue("receipts").jsonArray.map { entry ->
            val o = entry.jsonObject
            Receipt(o.getValue("key").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                o.getValue("kind").jsonPrimitive.content, o.getValue("at").jsonPrimitive.long,
                o.getValue("conversationId").jsonPrimitive.long, o.getValue("projectId").jsonPrimitive.long,
                o.getValue("verified").jsonPrimitive.boolean).also {
                check(it.key.matches(Regex("[a-f0-9]{64}")) && it.name.isNotBlank() && it.name.length <= 100 &&
                    it.conversationId > 0 && it.projectId >= 0 && it.kind in ProjectKind.entries.map { k -> k.name }) {
                    "Invalid project allocation history. Review Projects before creating more."
                }
            }
        }
        check(records.size <= 16 && records.map { it.key }.distinct().size == records.size &&
            records.map { it.conversationId }.distinct().size == records.size) { "Ambiguous project allocation history" }
        return records
    }

    fun save(records: List<Receipt>) {
        check(records.size <= 16)
        val parent = checkNotNull(file.parent()) { "Project allocation directory is missing" }
        check(!file.isSymbolicLink() && !parent.isSymbolicLink())
        check(parent.mkdirs() || parent.isDirectory()) { "Project allocation storage unavailable" }
        val bytes = buildJsonObject {
            put("version", 1)
            putJsonArray("receipts") { records.forEach { r -> add(buildJsonObject {
                put("key", r.key); put("name", r.name); put("kind", r.kind); put("at", r.at)
                put("conversationId", r.conversationId); put("projectId", r.projectId); put("verified", r.verified)
            }) } }
        }.toString().encodeToByteArray()
        check(bytes.size <= 64_000)
        // No non-atomic overwrite fallback: losing a receipt could repeat an insert.
        file.writeBytesAtomic(bytes)
    }
}
