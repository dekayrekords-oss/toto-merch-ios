package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.ToolRegistry
import com.meowmba.shared.agent.tools.ToolResult
import com.meowmba.shared.bridge.BridgeCrypto
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.workflows.WorkflowTools
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file tools' symlink cases, which need a JVM to set up.
 *
 * This completes the original's `traversalAndSymlinkEscapesAreRejected`, whose
 * `link/secret` half cannot run in `commonTest` because creating a symlink needs
 * `java.nio.file.Files`. It also covers the two things a link can do that a
 * `../` cannot: make the *listing* show an outside file, and make the draft
 * outbox write into another directory.
 *
 * Runs on the JVM only. The iOS `ModelFile` that these depend on is
 * compile-verified but not executed anywhere in this project, so "green" here
 * does not mean "green on a phone" — it means the logic is pinned and the iOS
 * side compiles.
 */
class WorkflowToolsSymlinkTest {

    private fun sandbox(prefix: String): Triple<ModelFile, ModelFile, ModelFile> {
        val base = ModelFile(PlatformTemp.createDirectory(prefix))
        val root = ModelFile("${base.path}/workspace").also { it.mkdirs() }
        val outside = ModelFile("${base.path}/outside").also { it.mkdirs() }
        return Triple(base, root, outside)
    }

    private fun ToolRegistry.outcome(id: String, args: Map<String, String>): ToolResult =
        runBlocking { byId(id)!!.execute(args) }

    private fun link(linkPath: String, target: ModelFile) {
        Files.createSymbolicLink(File(linkPath).toPath(), File(target.path).toPath())
    }

    @Test
    fun symlinkEscapeIsRejectedForReadWriteAndList() {
        val (_, root, outside) = sandbox("wf-link")
        ModelFile("${outside.path}/secret").writeBytesAtomic("secret".encodeToByteArray())
        link("${root.path}/link", outside)
        val tools = WorkflowTools.local(root)

        assertTrue(tools.outcome("files.read", mapOf("path" to "link/secret")) is ToolResult.Failure)
        assertTrue(
            tools.outcome("files.write", mapOf("path" to "link/secret", "content" to "bad")) is ToolResult.Failure,
        )

        val listing = tools.outcome("files.list", emptyMap()) as ToolResult.Success
        assertFalse(listing.output.contains("secret"), listing.output)
        assertEquals("secret", ModelFile("${outside.path}/secret").readText())
    }

    @Test
    fun listingOmitsTheLinkAndNeverDescendsThroughIt() {
        val (_, root, outside) = sandbox("wf-link2")
        ModelFile("${outside.path}/leaked.txt").writeBytesAtomic("leak".encodeToByteArray())
        link("${root.path}/link", outside)

        val listing = (WorkflowTools.local(root).outcome("files.list", emptyMap()) as ToolResult.Success).output

        // Two separate protections, both needed: `onEnter` refuses to descend
        // because the link's canonical target is outside the root, and the
        // isSymbolicLink filter keeps the link itself out of the listing so the
        // model is not handed a path it will then be refused on.
        assertFalse(listing.contains("link"), "the link entry itself is filtered out: $listing")
        assertFalse(listing.contains("leaked"), "and its contents are never walked: $listing")
    }

    @Test
    fun aDraftFolderThatIsASymlinkIsRefused() {
        val (_, root, outside) = sandbox("wf-draft-link")
        link("${root.path}/drafts", outside)
        val tools = WorkflowTools.local(root)
        val event = mapOf("source" to "s", "event_id" to "e")

        assertTrue(tools.outcome("drafts.prepare", event + mapOf("recipient" to "r", "body" to "b")) is ToolResult.Failure)
        assertTrue(tools.outcome("events.check", event) is ToolResult.Failure)
        assertTrue(tools.outcome("drafts.list", emptyMap()) is ToolResult.Failure)
        assertTrue(outside.listFiles().isEmpty(), "nothing may be written through the link")
    }

    @Test
    fun aDraftFileThatIsASymlinkIsRefused() {
        val (_, root, outside) = sandbox("wf-draft-file-link")
        ModelFile("${outside.path}/target").writeBytesAtomic("outside".encodeToByteArray())
        // The draft's filename is the SHA-256 of "source\0event", so the link has to
        // be created at the name the tool would choose. Computing it here is the
        // point: it proves the check fires on the path the tool actually resolves,
        // not on some path only a test knows about.
        ModelFile("${root.path}/drafts").mkdirs()
        val hash = BridgeCrypto.sha256Hex("s\u0000e".encodeToByteArray())
        link("${root.path}/drafts/$hash.json", ModelFile("${outside.path}/target"))

        val tools = WorkflowTools.local(root)
        val event = mapOf("source" to "s", "event_id" to "e")
        assertTrue(
            tools.outcome("drafts.prepare", event + mapOf("recipient" to "r", "body" to "b")) is ToolResult.Failure,
        )
        assertTrue(tools.outcome("events.check", event) is ToolResult.Failure)
        assertEquals("outside", ModelFile("${outside.path}/target").readText(), "the link target must be untouched")
    }

    @Test
    fun aDraftFolderLinkedInsideTheWorkspaceIsStillRefused() {
        val (_, root, _) = sandbox("wf-draft-inner-link")
        ModelFile("${root.path}/real-drafts").mkdirs()
        // The link points *inside* the workspace, so `drafts` canonicalises to a
        // path whose parent really is the canonical root: the canonical-parent
        // check is satisfied and the symlink check is the only thing refusing it.
        // That case is the reason both halves of that require exist -- and without
        // it the outbox would follow a link that its owner can repoint at will.
        link("${root.path}/drafts", ModelFile("${root.path}/real-drafts"))

        val tools = WorkflowTools.local(root)
        assertTrue(tools.outcome("drafts.list", emptyMap()) is ToolResult.Failure)
        assertTrue(
            tools.outcome(
                "drafts.prepare",
                mapOf("source" to "s", "event_id" to "e", "recipient" to "r", "body" to "b"),
            ) is ToolResult.Failure,
        )
        assertTrue(ModelFile("${root.path}/real-drafts").listFiles().isEmpty())
    }

    @Test
    fun draftsAreListedNewestFirst() {
        val (_, root, _) = sandbox("wf-draft-order")
        val drafts = ModelFile("${root.path}/drafts").also { it.mkdirs() }
        val older = ModelFile("${drafts.path}/older.json")
            .also { it.writeBytesAtomic("{}".encodeToByteArray()) }
        val newer = ModelFile("${drafts.path}/newer.json")
            .also { it.writeBytesAtomic("{}".encodeToByteArray()) }
        // Setting mtimes explicitly rather than sleeping: the ordering is what is
        // under test, and two writes can otherwise land in the same timestamp tick.
        File(older.path).setLastModified(1_000_000_000_000L)
        File(newer.path).setLastModified(1_500_000_000_000L)

        val output = (WorkflowTools.local(root).outcome("drafts.list", emptyMap()) as ToolResult.Success).output
        assertEquals("drafts/newer.json\ndrafts/older.json", output)
    }
}
