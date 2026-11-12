package com.meowmba.shared.workflows

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.relativeTo
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.Tool
import com.meowmba.shared.agent.tools.ToolDefinition
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.agent.tools.Workspace
import com.meowmba.shared.agent.walkTopDown
import com.meowmba.shared.bridge.BridgeCrypto

/**
 * The four file tools a business agent may call, sandboxed to one directory.
 *
 * Ported from `core/workflows/WorkflowTools.kt`. Business files are the same
 * assets visible through Meow's Files page, so every path goes through
 * [Workspace]: `../` and a symlink pointing out of the root are refused before
 * any read or write happens. That check is canonical rather than lexical — see
 * [Workspace.resolve] for why comparing raw strings is not enough.
 *
 * Three translations, each swapping a JVM-only type for a seam:
 *
 *  - `File.walkTopDown().onEnter { … }.maxDepth(6)` -> [walkTopDown], the shared
 *    traversal in `agent/ModelFileTree.kt`.
 *  - The `createTempFile` / `fd.sync()` / `ATOMIC_MOVE` dance -> 
 *    [ModelFile.writeBytesAtomic], which is the same stage-flush-rename
 *    contract, is already pinned by `WorkspaceSymlinkTest`, and creates parent
 *    directories — so the original's explicit `parentFile.mkdirs()` is gone
 *    rather than duplicated.
 *  - `MessageDigest.getInstance("SHA-256")` -> [BridgeCrypto.sha256Hex], which
 *    produces the identical lowercase hex (verified against the JVM actual).
 *
 * ## Two deliberate departures
 *
 * **`File.equals` in the `onEnter` guard.** The original wrote
 * `it.canonicalFile == root.canonicalFile`. `java.io.File.equals` is *path*-
 * based, so that compared canonical path strings; `ModelFile` does not override
 * `equals`, so the same expression here would be reference identity — always
 * false for a freshly-constructed entry, which would make `onEnter` refuse even
 * the root and return a listing with nothing in it. It is written as a path
 * comparison instead. The root's canonical path is also hoisted out of the
 * lambda: the original re-canonicalised the root once per entry, and on iOS
 * that is a `realpath(3)` syscall per directory.
 *
 * **`take(201)` became `take(200)`.** The original collected 201 entries and
 * then rendered `take(200)`, but the "Listing limited to 200 entries" footer is
 * appended unconditionally, so the 201st element could not reach any output.
 * Dropping it bounds the walk one entry earlier with no observable difference.
 */
object WorkflowTools {
    fun local(root: ModelFile): ToolRegistry {
        val workspace = Workspace(root)
        return ToolRegistry().apply {
            WorkflowDrafts.register(this, root)
            register(
                action("files.list", "List business files", mapOf("path" to "string")) { args ->
                    val dir = if (args["path"].isNullOrBlank()) root else workspace.resolve(args.getValue("path"))
                    require(dir?.isDirectory() == true) { "Folder not found" }
                    val canonicalRoot = root.canonicalPath()
                    val entries = dir.walkTopDown(maxDepth = 6, onEnter = { entry ->
                        entry.canonicalPath().startsWith("$canonicalRoot/") ||
                            entry.canonicalPath() == canonicalRoot
                    })
                        .filter { !it.isSymbolicLink() }
                        .take(200)
                        .toList()
                    entries.joinToString("\n") { it.relativeTo(root) }.take(11_000) +
                        "\nListing limited to 200 entries, depth 6 and 11,000 characters. Use a subfolder to narrow it."
                },
            )
            register(
                action(
                    "files.read",
                    "Read a business text file page (offset is characters, default 0)",
                    mapOf("path" to "string", "offset" to "integer"),
                ) { args ->
                    val file = workspace.resolve(args["path"].orEmpty())
                    require(file?.isFile() == true) {
                        "File not found: ${args["path"]}. Use the exact path from the job or files.list."
                    }
                    require(file.length() <= 100_000) { "File is too large; split it into smaller inputs" }
                    // `ModelFile.readText` reports "missing or unreadable" as null where the
                    // original threw; the require above already excluded "missing", so a null
                    // here is a genuine read failure and must still be a tool failure.
                    val text = file.readText() ?: error("File could not be read: ${args["path"]}")
                    val offset = args["offset"]?.toIntOrNull() ?: 0
                    // A message guard, not a safety one: `substring` below throws for
                    // any offset outside the text, so the tool reports a Failure with
                    // or without this line. What it buys is "Offset outside file"
                    // rather than a raw index exception, which is the difference
                    // between a model correcting itself and retrying the same call.
                    // `filesReadRefusesMissingOversizedAndOutOfRangeReads` asserts the
                    // message for exactly that reason.
                    require(offset in 0..text.length) { "Offset outside file" }
                    val end = minOf(offset + 10_000, text.length)
                    "File: ${args["path"]}; characters $offset..$end of ${text.length}; " +
                        (if (end < text.length) "more: call files.read with offset=$end" else "end of file") +
                        "\n" + text.substring(offset, end)
                },
            )
            register(
                action(
                    "files.write",
                    "Write a business report or draft and verify it",
                    mapOf("path" to "string", "content" to "string"),
                    writes = true,
                ) { args ->
                    val file = workspace.resolve(args["path"].orEmpty()) ?: error("Path leaves the workspace")
                    val content = args["content"] ?: error("Missing content")
                    require(content.length <= 100_000) { "Content too large" }
                    val bytes = content.encodeToByteArray()
                    file.writeBytesAtomic(bytes)
                    // No test on this machine can detect this line's removal: it fires
                    // only if the atomic write landed *different* bytes, which needs a
                    // filesystem that silently drops writes. Verified by mutation —
                    // deleting it changes no result. Kept as a cheap post-condition,
                    // not mistaken for a pinned guard.
                    check(file.readBytes()?.contentEquals(bytes) == true) { "Written contents did not match" }
                    val digest = BridgeCrypto.sha256Hex(bytes)
                    "Verified file: ${file.relativeTo(root)}\nBytes: ${bytes.size}\nSHA-256: $digest"
                },
            )
            register(
                action(
                    "files.csv_summary",
                    "Calculate exact decimal totals from a CSV. Read the CSV to learn its columns and values. " +
                        "amount_column is required. Optional filter_column/filter_value selects equal rows, " +
                        "case-insensitive by default; set case_sensitive=true for case-sensitive values. Column " +
                        "names accept unambiguous case aliases only; output states the actual column and matching mode.",
                    mapOf(
                        "path" to "string",
                        "amount_column" to "string",
                        "filter_column" to "string",
                        "filter_value" to "string",
                        "case_sensitive" to "boolean",
                    ),
                ) { args ->
                    val file = workspace.resolve(args["path"].orEmpty())
                    require(file?.isFile() == true) {
                        "CSV not found: ${args["path"]}. Use the exact path in the original job or files.list."
                    }
                    require(file.length() <= 100_000) { "CSV is over 100KB; split the input" }
                    val mode = args["case_sensitive"].orEmpty()
                    require(mode in setOf("", "true", "false")) { "case_sensitive must be true or false" }
                    CsvSummary.summarize(
                        file.readText() ?: error("CSV could not be read: ${args["path"]}"),
                        args["amount_column"].orEmpty(),
                        args["filter_column"],
                        args["filter_value"],
                        mode == "true",
                    )
                },
            )
        }
    }

    /**
     * A tool whose whole body is "run this and report the message".
     *
     * Every failure path above is a `require`/`error`/`check`, so the catch here
     * is what turns a rejected argument into a [ToolResult.Failure] the model can
     * read and correct, rather than an exception crossing the tool boundary.
     */
    private fun action(
        id: String,
        label: String,
        args: Map<String, String>,
        writes: Boolean = false,
        run: (Map<String, String>) -> String,
    ) = object : Tool {
        override val definition = ToolDefinition(
            id, label, label, args,
            riskLevel = if (writes) RiskLevel.MODERATE else RiskLevel.SAFE,
            confirmationPolicy = if (writes) ConfirmationPolicy.ALWAYS else ConfirmationPolicy.NONE,
        )

        override suspend fun execute(args: Map<String, String>): ToolResult = try {
            ToolResult.Success(run(args))
        } catch (e: Exception) {
            ToolResult.Failure(e.message ?: "File operation failed")
        }
    }
}
