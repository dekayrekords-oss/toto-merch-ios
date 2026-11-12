package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.ConfirmationPolicy
import com.meowmba.shared.agent.tools.RiskLevel
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.workflows.WorkflowTools
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The four file tools a business agent may call, over a real sandbox directory.
 *
 * These are the first tests in the port that exercise a *tool* end to end
 * against a real filesystem rather than a fake. That matters because most of
 * what these tools do is arithmetic and bounds rather than policy: a 10,000
 * character page, a depth-6 walk, a 100KB ceiling, a 200-entry cap. A fake
 * registry could not pin any of it, and the original's only coverage here was
 * the escape test plus whatever the engine happened to exercise.
 *
 * The symlink half of the original's `traversalAndSymlinkEscapesAreRejected`
 * needs `java.nio.file.Files.createSymbolicLink`, so it lives in the JVM-only
 * `WorkflowToolsSymlinkTest` alongside the rest of the sandbox's symlink cases.
 */
class WorkflowToolsTest {

    // -- helpers ----------------------------------------------------------

    /** A sandbox root plus the directory containing it, for escape attempts. */
    private fun sandbox(): Pair<ModelFile, ModelFile> {
        val base = ModelFile(PlatformTemp.createDirectory("wf-tools"))
        return base to ModelFile("${base.path}/workspace").also { it.mkdirs() }
    }

    private fun registry(root: ModelFile): ToolRegistry = WorkflowTools.local(root)

    /** `Tool.execute` is suspending; the tests that need to await it just block. */
    private fun ToolRegistry.outcome(id: String, args: Map<String, String>): ToolResult =
        runBlocking { byId(id)!!.execute(args) }

    private fun ModelFile.write(text: String) = writeBytesAtomic(text.encodeToByteArray())

    // -- files.list -------------------------------------------------------

    @Test
    fun filesListReportsRelativePathsAndStatesItsOwnBounds() {
        val (_, root) = sandbox()
        ModelFile("${root.path}/orders.csv").write("id,amount\nA,1\n")
        ModelFile("${root.path}/reports/q1.txt").write("q1")
        val listing = (registry(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        assertTrue(listing.contains("orders.csv"), listing)
        assertTrue(listing.contains("reports/q1.txt"), "nested paths are relative to the root: $listing")
        // The bounds are part of the tool's contract with the model: it has to be
        // told that the listing was cut short, or it will conclude the workspace
        // is empty of what it was looking for.
        assertTrue(listing.contains("depth 6"), listing)
        assertTrue(listing.contains("200 entries"), listing)
        assertTrue(listing.contains("11,000 characters"), listing)
    }

    @Test
    fun filesListBeginsWithABlankLineBecauseTheRootIsItsOwnFirstEntry() {
        val (_, root) = sandbox()
        ModelFile("${root.path}/only.txt").write("x")
        val listing = (registry(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        // The walk yields the receiver first, and the receiver's path relative to
        // the root is the empty string. Preserved rather than "tidied": the model
        // is the consumer of this text and its shape is what it was tuned against.
        assertTrue(listing.startsWith("\n"), listing.take(40))
    }

    @Test
    fun filesListStopsAtDepthSixAndSaysSoByOmittingDeeperEntries() {
        val (_, root) = sandbox()
        // root/a/b/c/d/e/deep.txt is depth 6; root/a/b/c/d/e/f/too-deep.txt is depth 7.
        ModelFile("${root.path}/a/b/c/d/e/deep.txt").write("d")
        ModelFile("${root.path}/a/b/c/d/e/f/too-deep.txt").write("x")
        val listing = (registry(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        assertTrue(listing.contains("a/b/c/d/e/deep.txt"), listing)
        assertFalse(listing.contains("too-deep.txt"), "the depth bound must actually cut the walk: $listing")
    }

    @Test
    fun filesListAcceptsASubfolderAndRefusesOneThatIsMissingOrOutside() {
        val (base, root) = sandbox()
        ModelFile("${root.path}/reports/q1.txt").write("q1")
        ModelFile("${base.path}/outside/secret.txt").write("secret")
        val tools = registry(root)

        val nested = (tools.outcome("files.list", mapOf("path" to "reports")) as ToolResult.Success).output
        assertTrue(nested.contains("q1.txt"), nested)
        assertFalse(nested.contains("secret"), nested)

        assertTrue(tools.outcome("files.list", mapOf("path" to "nope")) is ToolResult.Failure)
        assertTrue(tools.outcome("files.list", mapOf("path" to "../outside")) is ToolResult.Failure)
    }

    @Test
    fun filesListKeepsAtMostTwoHundredEntries() {
        val (_, root) = sandbox()
        // 250 short names, so the character cap below cannot mask the entry cap.
        repeat(250) { ModelFile("${root.path}/f${it.toString().padStart(3, '0')}.txt").write("x") }
        val listing = (registry(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        // Counted rather than named: `listFiles` order is filesystem-dependent, so
        // which 200 survive is not assertable, but that there are exactly 200 is.
        // The first line is the root itself, whose relative path is "".
        val body = listing.substringBefore("\nListing limited")
        assertEquals(200, body.split("\n").size, "the walk must stop at 200 entries")
    }

    @Test
    fun filesListTruncatesAtElevenThousandCharacters() {
        val (_, root) = sandbox()
        // 60-character names, so 200 entries overflow the character budget and the
        // entry cap is no longer what is doing the limiting.
        repeat(250) { ModelFile("${root.path}/${it.toString().padStart(3, '0')}${"n".repeat(53)}.txt").write("x") }
        val listing = (registry(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        val body = listing.substringBefore("\nListing limited")
        // 199 names of 60 characters plus 199 separators is 12,139 characters, so a
        // missing cap would show up here as a longer body rather than as a failure.
        assertEquals(11_000, body.length, "the listing body must be cut to the character budget")
    }

    // -- files.read -------------------------------------------------------

    @Test
    fun filesReadPagesByCharacterOffsetAndSaysHowToContinue() {
        val (_, root) = sandbox()
        ModelFile("${root.path}/big.txt").write("x".repeat(25_000))
        val tools = registry(root)

        val first = (tools.outcome("files.read", mapOf("path" to "big.txt")) as ToolResult.Success).output
        assertTrue(first.contains("characters 0..10000 of 25000"), first.take(80))
        assertTrue(first.contains("more: call files.read with offset=10000"), first.take(120))
        assertEquals(10_000, first.substringAfter("\n").length)

        val second =
            (tools.outcome("files.read", mapOf("path" to "big.txt", "offset" to "20000")) as ToolResult.Success).output
        assertTrue(second.contains("characters 20000..25000 of 25000"), second.take(80))
        assertTrue(second.contains("end of file"), second.take(120))
        assertEquals(5_000, second.substringAfter("\n").length)
    }

    @Test
    fun filesReadRefusesMissingOversizedAndOutOfRangeReads() {
        val (_, root) = sandbox()
        val tools = registry(root)
        assertTrue(tools.outcome("files.read", mapOf("path" to "absent.txt")) is ToolResult.Failure)

        ModelFile("${root.path}/huge.txt").write("y".repeat(100_001))
        assertTrue(tools.outcome("files.read", mapOf("path" to "huge.txt")) is ToolResult.Failure)

        ModelFile("${root.path}/small.txt").write("abc")
        // The range check is not what makes an out-of-range offset fail: `substring`
        // throws anyway and the tool still reports a Failure either way. What the
        // check buys is the *message* -- "Offset outside file" instead of a raw
        // index exception -- which is what lets a model correct itself rather than
        // retry the same call. So the message is what gets asserted.
        for (offset in listOf("4", "-1")) {
            val failure = tools.outcome("files.read", mapOf("path" to "small.txt", "offset" to offset)) as
                ToolResult.Failure
            assertTrue(failure.error.contains("Offset outside file"), "$offset -> ${failure.error}")
        }
        // Exactly at the end is in range: it reports an empty tail rather than
        // failing, which is what lets a model page to the end without a special case.
        val atEnd =
            (tools.outcome("files.read", mapOf("path" to "small.txt", "offset" to "3")) as ToolResult.Success).output
        assertTrue(atEnd.contains("end of file"), atEnd)
    }

    @Test
    fun filesReadIsNotAFolderListingAndFilesListIsNotARead() {
        val (_, root) = sandbox()
        ModelFile("${root.path}/dir/inner.txt").write("inner")
        val tools = registry(root)
        assertTrue(tools.outcome("files.read", mapOf("path" to "dir")) is ToolResult.Failure)
        // A missing path on files.list is a failure, not an empty success -- the
        // model must not be told "nothing here" when it asked about a typo.
        assertTrue(tools.outcome("files.list", mapOf("path" to "dir/typo")) is ToolResult.Failure)
    }

    // -- files.write ------------------------------------------------------

    @Test
    fun filesWriteVerifiesAndReportsTheSha256OfWhatItActuallyWrote() {
        val (_, root) = sandbox()
        val content = "total 25.25"
        val result =
            (registry(root).outcome("files.write", mapOf("path" to "reports/q1.txt", "content" to content)) as ToolResult.Success).output

        assertEquals(content, ModelFile("${root.path}/reports/q1.txt").readText())
        assertTrue(result.contains("Verified file: reports/q1.txt"), result)
        assertTrue(result.contains("Bytes: 11"), result)
        // Pinned to a digest computed outside this codebase. Comparing the tool's
        // output to the tool's own hashing would pass even if the hash were wrong.
        assertTrue(
            result.contains("SHA-256: 9a743d41413cd73016ef108cda9b478fbb307e187a16d00eed4f3b52c6e28f2a"),
            result,
        )
    }

    @Test
    fun filesWriteCreatesParentsAndReplacesRatherThanAppends() {
        val (_, root) = sandbox()
        val tools = registry(root)
        tools.outcome("files.write", mapOf("path" to "a/b/c.txt", "content" to "a considerably longer first payload"))
        tools.outcome("files.write", mapOf("path" to "a/b/c.txt", "content" to "tiny"))
        assertEquals("tiny", ModelFile("${root.path}/a/b/c.txt").readText())
    }

    @Test
    fun filesWriteRefusesEscapesOversizedContentAndMissingContent() {
        val (base, root) = sandbox()
        val tools = registry(root)

        assertTrue(
            tools.outcome("files.write", mapOf("path" to "../outside.txt", "content" to "bad")) is ToolResult.Failure,
        )
        assertFalse(ModelFile("${base.path}/outside.txt").exists(), "nothing may be written outside the sandbox")

        assertTrue(
            tools.outcome("files.write", mapOf("path" to "x.txt", "content" to "z".repeat(100_001))) is
                ToolResult.Failure,
        )
        assertFalse(ModelFile("${root.path}/x.txt").exists(), "an oversized write must not leave a partial file")

        assertTrue(tools.outcome("files.write", mapOf("path" to "x.txt")) is ToolResult.Failure, "content is required")
    }

    // -- files.csv_summary ------------------------------------------------

    @Test
    fun filesCsvSummaryRunsTheExactDecimalPathOverTheRealFile() {
        val (_, root) = sandbox()
        ModelFile("${root.path}/orders.csv").write("id,amount,status\nA,10.10,paid\nB,20.20,unpaid\nC,5.05,unpaid\n")
        val tools = registry(root)

        val result = (
            tools.outcome(
                "files.csv_summary",
                mapOf(
                    "path" to "orders.csv",
                    "amount_column" to "amount",
                    "filter_column" to "status",
                    "filter_value" to "unpaid",
                ),
            ) as ToolResult.Success
            ).output
        assertTrue(result.contains("Sum of amount: 25.25"), result)

        assertTrue(tools.outcome("files.csv_summary", mapOf("path" to "orders.csv")) is ToolResult.Failure)
        assertTrue(
            tools.outcome(
                "files.csv_summary",
                mapOf("path" to "orders.csv", "amount_column" to "amount", "case_sensitive" to "yes"),
            ) is ToolResult.Failure,
        )
        assertTrue(
            tools.outcome("files.csv_summary", mapOf("path" to "nope.csv", "amount_column" to "amount")) is
                ToolResult.Failure,
        )
    }

    // -- the registry as a whole ------------------------------------------

    @Test
    fun theRegistryIsExactlyTheseSevenToolsAndOnlyWritingNeedsApproval() {
        val (_, root) = sandbox()
        val byId = registry(root).definitions().associateBy { it.id }

        // Pins the wiring too: `WorkflowTools.local` is what registers the outbox
        // tools, so a model offered this registry can check and prepare drafts.
        assertEquals(
            setOf(
                "files.list", "files.read", "files.write", "files.csv_summary",
                "events.check", "drafts.prepare", "drafts.list",
            ),
            byId.keys,
        )
        assertEquals(RiskLevel.MODERATE, byId.getValue("files.write").riskLevel)
        assertEquals(ConfirmationPolicy.ALWAYS, byId.getValue("files.write").confirmationPolicy)
        for (id in byId.keys - "files.write") {
            assertEquals(RiskLevel.SAFE, byId.getValue(id).riskLevel, id)
            assertEquals(ConfirmationPolicy.NONE, byId.getValue(id).confirmationPolicy, id)
        }
    }

    // -- the ported escape test -------------------------------------------

    @Test
    fun traversalEscapeIsRejectedForReadWriteAndList() {
        val (base, root) = sandbox()
        val outside = ModelFile("${base.path}/outside").also { it.mkdirs() }
        ModelFile("${outside.path}/secret").write("secret")
        val tools = registry(root)

        assertTrue(tools.outcome("files.read", mapOf("path" to "../outside/secret")) is ToolResult.Failure)
        assertTrue(
            tools.outcome("files.write", mapOf("path" to "../outside/secret", "content" to "bad")) is
                ToolResult.Failure,
        )

        val listing = tools.outcome("files.list", emptyMap()) as ToolResult.Success
        assertFalse(listing.output.contains("secret"), listing.output)
        assertEquals("secret", ModelFile("${outside.path}/secret").readText())
    }
}
