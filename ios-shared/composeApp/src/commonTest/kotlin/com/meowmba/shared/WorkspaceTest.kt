package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.Workspace
import com.meowmba.shared.core.PlatformTemp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sandbox that makes file tools safe to hand to a model.
 *
 * The original has no dedicated `Workspace` test — its escape behaviour is only
 * exercised indirectly, through the tools, by one case in
 * `BusinessWorkflowTest`. That case cannot be ported until the tools are, so the
 * guarantees the class documents are pinned here directly instead: an escape
 * that only one tool happens to exercise is not pinned at all.
 *
 * Everything runs against a real directory, because the whole class is about
 * what the filesystem actually does with `..` and symlinks. A fake would be
 * testing the fake.
 *
 * The symlink case lives in `jvmTest` — creating a symlink needs
 * `java.nio.file.Files`, which is JVM-only.
 *
 * **Six of these tests are `runTest` bodies and four are not, on purpose.**
 * `read` and `write` go through the project-aware `resolveRead`/`resolveWrite`,
 * which consult the coroutine context, so they are `suspend`; `resolve`,
 * `delete` and `list` never touch the project scope and stayed plain. The split
 * in this file is the same split as in the class, and it is a useful check that
 * it stayed that way.
 */
class WorkspaceTest {

    private class Sandbox(
        val workspace: Workspace,
        val base: ModelFile,
        val outside: ModelFile,
        val sibling: ModelFile,
    )

    /**
     * ```
     * base/
     *   workspace/        <- the sandbox root
     *   outside/secret    <- a traversal target
     *   workspace-extra/  <- a sibling whose name shares the root's prefix
     * ```
     */
    private fun sandbox(): Sandbox {
        val base = ModelFile(PlatformTemp.createDirectory("ws"))
        val root = ModelFile("${base.path}/workspace")
        val outside = ModelFile("${base.path}/outside").also { it.mkdirs() }
        val sibling = ModelFile("${base.path}/workspace-extra").also { it.mkdirs() }
        ModelFile("${outside.path}/secret").writeBytesAtomic("secret".encodeToByteArray())
        ModelFile("${sibling.path}/secret").writeBytesAtomic("secret".encodeToByteArray())
        return Sandbox(Workspace(root), base, outside, sibling)
    }

    @Test
    fun traversalToASiblingDirectoryIsRefused() = runTest {
        val s = sandbox()
        assertNull(s.workspace.resolve("../outside/secret"), "resolve must refuse an escape")
        assertNull(s.workspace.read("../outside/secret"))
        assertFalse(s.workspace.write("../outside/secret", "bad"))
        // The refused write must not have touched the file it aimed at.
        assertEquals("secret", ModelFile("${s.outside.path}/secret").readText())
    }

    @Test
    fun aSiblingSharingTheRootsNamePrefixIsNotInsideIt() {
        val s = sandbox()
        // "workspace-extra" starts with "workspace" as text. Only the separator
        // in the containment check keeps it out, so this is the test that pins
        // that separator: drop it and this path is wrongly accepted.
        assertNull(s.workspace.resolve("../workspace-extra/secret"))
    }

    @Test
    fun aLeadingSlashAddressesTheRootNotTheFilesystemRoot() {
        val s = sandbox()
        val resolved = s.workspace.resolve("/etc/passwd")
        assertNotNull(resolved)
        // Trimmed to root-relative, exactly as the original did -- this is the
        // real /etc/passwd's *name* under the sandbox, not the file itself.
        assertEquals("${s.workspace.root.path}/etc/passwd", resolved.path)
    }

    @Test
    fun blankPathsResolveToNothing() {
        val s = sandbox()
        assertNull(s.workspace.resolve(""))
        assertNull(s.workspace.resolve("   "))
        assertNull(s.workspace.resolve("/"))
        assertNull(s.workspace.resolve("\\"))
    }

    @Test
    fun writeThenReadRoundTripsAndCreatesParents() = runTest {
        val s = sandbox()
        assertTrue(s.workspace.write("notes/report.txt", "hello"))
        assertEquals("hello", s.workspace.read("notes/report.txt"))
        assertTrue(ModelFile("${s.workspace.root.path}/notes/report.txt").isFile())
    }

    @Test
    fun readingSomethingAbsentIsNull() = runTest {
        val s = sandbox()
        assertNull(s.workspace.read("nope.txt"))
        // A directory is not a text file, even though it resolves.
        assertTrue(s.workspace.write("adir/keep.txt", "x"))
        assertNull(s.workspace.read("adir"))
    }

    /**
     * The one guard here that a test on this machine cannot pin.
     *
     * Deleting the emptiness check from [Workspace.delete] and re-running leaves
     * every test green, because `java.io.File.delete()` refuses a non-empty
     * directory on its own — the assertion below is satisfied twice over. On iOS
     * the same deletion would be catastrophic: `NSFileManager.removeItemAtPath`
     * removes a directory *recursively*, so the explicit check is the only
     * protection there.
     *
     * So this test documents the contract and the mutation result together. It
     * is not evidence that the guard exists; it is evidence that the *observable
     * behaviour* is right, and a note that on one platform the behaviour is
     * provided by the platform and on the other by our own line.
     */
    @Test
    fun deleteRemovesFilesAndEmptyDirectoriesOnly() = runTest {
        val s = sandbox()
        s.workspace.write("a/b.txt", "x")
        assertFalse(s.workspace.delete("a"), "a non-empty directory must not be removed")
        assertTrue(s.workspace.delete("a/b.txt"))
        assertTrue(s.workspace.delete("a"), "now empty, so it may go")
        assertFalse(s.workspace.delete("a"), "already gone")
        assertFalse(s.workspace.delete("../outside/secret"), "escape attempt")
        assertTrue(ModelFile("${s.outside.path}/secret").isFile(), "the outside file survives")
    }

    @Test
    fun listIsSortedAndMarksDirectories() = runTest {
        val s = sandbox()
        s.workspace.write("zeta.txt", "1")
        s.workspace.write("alpha/one.txt", "2")
        s.workspace.write("alpha/two.txt", "3")
        s.workspace.write("beta.txt", "4")
        assertEquals(
            listOf("alpha/", "alpha/one.txt", "alpha/two.txt", "beta.txt", "zeta.txt"),
            s.workspace.list(),
        )
    }

    @Test
    fun listOfAMissingOrNonDirectoryPathIsEmpty() = runTest {
        val s = sandbox()
        assertTrue(s.workspace.list("nope").isEmpty())
        assertTrue(s.workspace.list("../outside").isEmpty(), "escape attempt")
        s.workspace.write("file.txt", "x")
        assertTrue(s.workspace.list("file.txt").isEmpty())
    }

    @Test
    fun constructingAWorkspaceCreatesItsRoot() {
        val base = ModelFile(PlatformTemp.createDirectory("ws-init"))
        val root = ModelFile("${base.path}/not-yet")
        assertFalse(root.exists())
        Workspace(root)
        assertTrue(root.isDirectory(), "the constructor must create the sandbox root")
    }
}
