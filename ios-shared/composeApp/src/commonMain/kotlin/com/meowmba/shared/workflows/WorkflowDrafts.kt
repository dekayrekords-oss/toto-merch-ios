package com.meowmba.shared.workflows

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.bridge.BridgeCrypto
import com.meowmba.shared.core.PlatformLock
import com.meowmba.shared.core.PlatformTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Durable, deduplicated review outbox. It deliberately has NO send function.
 * Event keys must include the service/account and its stable event identifier.
 *
 * Ported from `core/workflows/WorkflowDrafts.kt`. Four translations:
 *
 *  - `@Synchronized` -> [PlatformLock]. The original synchronised on the
 *    `WorkflowDrafts` singleton, so one lock at object scope is the same scope.
 *    Only `prepare` was synchronised in the original and only `prepare` is
 *    locked here — `events.check` and `drafts.list` stay lock-free rather than
 *    being "hardened", because widening a lock is not a translation.
 *  - `System.currentTimeMillis()` -> [PlatformTime.currentMillis].
 *  - `MessageDigest.getInstance("SHA-256")` -> [BridgeCrypto.sha256Hex], the
 *    same lowercase hex.
 *  - The temp-file / `fd.sync()` / `ATOMIC_MOVE` dance -> 
 *    [ModelFile.writeBytesAtomic]. Note this **drops `REPLACE_EXISTING`**, which
 *    the original used: it was dead there too, because `prepare` returns early
 *    when the destination exists, so the move never had a target to replace.
 *
 * The deduplication key is the SHA-256 of `source\u0000event`, so a repeated
 * `drafts.prepare` for the same event lands on the same filename and the early
 * return keeps the first draft. That is the whole point of the outbox: a model
 * that retries must not produce a second reply to the same customer.
 */
object WorkflowDrafts {

    /** Guards [prepareLocked]; mirrors the original's `@Synchronized`. */
    private val lock = PlatformLock()

    fun register(registry: ToolRegistry, root: ModelFile) {
        registry.register(
            tool(
                "events.check",
                "Check whether a service event already has a saved reply draft",
                mapOf("source" to "service/account", "event_id" to "stable message or signup ID"),
            ) { args ->
                val file = destination(root, args)
                if (file.exists()) {
                    "Already drafted. Do not reply again. Review: drafts/${file.name}"
                } else {
                    "No saved draft for this event. This does not prove whether any external reply was sent."
                }
            },
        )
        registry.register(
            tool(
                "drafts.prepare",
                "Save a reply to the local review outbox without sending it. One draft per source/event; " +
                    "repeated calls never overwrite it.",
                mapOf(
                    "source" to "service/account",
                    "event_id" to "stable event ID",
                    "recipient" to "recipient",
                    "subject" to "subject",
                    "body" to "reply draft",
                ),
            ) { args -> lock.withLock { prepareLocked(root, args) } },
        )
        registry.register(
            tool(
                "drafts.list",
                "List up to 100 local review-outbox draft paths; use files.read to review contents",
                emptyMap(),
            ) {
                // The original's `listFiles()?` chain handled a null array from a
                // non-directory; `ModelFile.listFiles` returns an empty list instead,
                // which falls through to the same "No drafts saved." via `ifBlank`.
                val drafts = folder(root).listFiles()
                    // `File.extension` is `name.substringAfterLast('.', "")`, so this is
                    // the same predicate, not an approximation of it.
                    .filter { it.isFile() && it.name.substringAfterLast('.', "") == "json" }
                    .sortedByDescending { it.lastModified() }
                    .take(100)
                drafts.joinToString("\n") { "drafts/${it.name}" }.ifBlank { "No drafts saved." }
            },
        )
    }

    /**
     * The outbox directory, created on demand.
     *
     * Both checks are load-bearing. The symlink test refuses a `drafts` entry
     * that is really a link elsewhere; the canonical-parent test refuses one
     * that resolves outside the workspace even though it is not itself a link
     * (a link higher up the chain, or a root that is itself a link). Without the
     * second check the outbox could be written into another app's directory.
     */
    private fun folder(root: ModelFile): ModelFile {
        root.mkdirs()
        val folder = ModelFile("${root.path.trimEnd('/')}/drafts")
        require(
            !folder.isSymbolicLink() &&
                // Tautological whenever `drafts` is not itself a link: its canonical
                // path is then always `canonical(root)/drafts`, whose parent is
                // `canonical(root)`. Verified by mutation — deleting this half changes
                // no test result, and neither does deleting the half above, because for
                // an outward link the two disagree only on which one fires.
                // `aDraftFolderLinkedInsideTheWorkspaceIsStillRefused` is the case where
                // they *do* disagree and the symlink check is the one doing the work.
                // This half is kept as defence in depth: a future `canonicalPath` that
                // stopped resolving ancestors would otherwise let the outbox escape.
                ModelFile(folder.canonicalPath()).parent()?.path == root.canonicalPath(),
        ) { "Draft folder must stay in the business workspace" }
        folder.mkdirs()
        return folder
    }

    /**
     * Where the draft for this event lives: `drafts/<sha256 of source\0event>.json`.
     *
     * The identity separator is a NUL so that `("a", "b\0c")` and `("a\0b", "c")`
     * cannot collide into one filename. Both halves are length-capped at 500 to
     * keep a model from hashing an unbounded string.
     */
    private fun destination(root: ModelFile, args: Map<String, String>): ModelFile {
        val source = args["source"]?.trim().orEmpty()
        val event = args["event_id"]?.trim().orEmpty()
        require(source.isNotEmpty() && event.isNotEmpty() && source.length <= 500 && event.length <= 500) {
            "Use a stable source/account and event ID"
        }
        val identity = "$source\u0000$event"
        val hash = BridgeCrypto.sha256Hex(identity.encodeToByteArray())
        return ModelFile("${folder(root).path}/$hash.json").also {
            require(!it.isSymbolicLink()) { "Draft cannot be a symbolic link" }
        }
    }

    /**
     * The body of `drafts.prepare`, split out only so the early "existing draft"
     * return can happen inside the lock — a lambda cannot `return` non-locally.
     *
     * Order matters and is preserved: the dedup check runs *before* the
     * recipient/body validation, so re-preparing an event with an empty body
     * reports "Existing draft kept" rather than failing. That is the original's
     * behaviour and it is the safer one — a retry should not turn into an error
     * the model then tries to "fix" by inventing a body.
     */
    private fun prepareLocked(root: ModelFile, args: Map<String, String>): String {
        val file = destination(root, args)
        if (file.exists()) return "Existing draft kept; nothing sent or overwritten. Review: drafts/${file.name}"
        require(!args["recipient"].isNullOrBlank() && !args["body"].isNullOrBlank()) {
            "Recipient and body are required"
        }
        require(args.values.sumOf { it.length } <= 30_000) { "Draft too large" }
        check(folder(root).listFiles().size < 2000) { "Outbox full. Review and archive drafts in Files." }
        val contents = buildJsonObject {
            put("status", "DRAFT_REQUIRES_REVIEW")
            put("sent", false)
            put("createdAt", PlatformTime.currentMillis())
            for (key in listOf("source", "event_id", "recipient", "subject", "body")) {
                put(key, args[key].orEmpty())
            }
        }.toString().encodeToByteArray()
        file.writeBytesAtomic(contents)
        check(file.readBytes()?.contentEquals(contents) == true) { "Draft verification failed" }
        return "Draft saved and verified, NOT sent. Review: drafts/${file.name}"
    }

    private fun tool(
        id: String,
        description: String,
        schema: Map<String, String>,
        action: (Map<String, String>) -> String,
    ) = object : Tool {
        override val definition = ToolDefinition(
            id, id, description, schema,
            riskLevel = RiskLevel.SAFE,
            confirmationPolicy = ConfirmationPolicy.NONE,
        )

        override suspend fun execute(args: Map<String, String>): ToolResult = try {
            ToolResult.Success(action(args))
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "Outbox operation failed")
        }
    }
}
