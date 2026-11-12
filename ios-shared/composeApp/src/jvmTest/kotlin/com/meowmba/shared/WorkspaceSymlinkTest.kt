package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.tools.Workspace
import com.meowmba.shared.core.PlatformTemp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the sandbox that need a JVM to set up.
 *
 * A symlink is the one escape a purely lexical check cannot catch: `link/secret`
 * looks like an ordinary relative path, and only resolving the link reveals that
 * it lands outside the root. Creating one needs `java.nio.file.Files`, which is
 * JVM-only, so this lives here rather than in `commonTest` — and that means it
 * runs on the JVM only, not for iOS. Saying so is the honest thing; the iOS
 * `canonicalPath` that this depends on is compile-verified but not executed
 * anywhere in this project.
 */
class WorkspaceSymlinkTest {

    @Test
    fun aSymlinkPointingOutsideTheRootIsRefused() = runTest {
        val base = ModelFile(PlatformTemp.createDirectory("ws-link"))
        val root = ModelFile("${base.path}/workspace")
        val outside = ModelFile("${base.path}/outside").also { it.mkdirs() }
        ModelFile("${outside.path}/secret").writeBytesAtomic("secret".encodeToByteArray())
        val workspace = Workspace(root)

        Files.createSymbolicLink(
            File("${root.path}/link").toPath(),
            File(outside.path).toPath(),
        )

        // The link itself is inside the root, so a lexical check passes it. Only
        // canonicalisation reveals that link/secret is outside.
        assertNull(workspace.resolve("link/secret"), "resolve must follow the link and refuse")
        assertNull(workspace.read("link/secret"))
        assertFalse(workspace.write("link/secret", "bad"))
        assertEquals("secret", ModelFile("${outside.path}/secret").readText())
    }

    @Test
    fun isSymbolicLinkReportsTheEntryItselfNotWhatItPointsAt() {
        val base = ModelFile(PlatformTemp.createDirectory("ws-link2"))
        val root = ModelFile("${base.path}/workspace")
        val outside = ModelFile("${base.path}/outside").also { it.mkdirs() }
        Workspace(root)
        Files.createSymbolicLink(
            File("${root.path}/link").toPath(),
            File(outside.path).toPath(),
        )

        assertTrue(ModelFile("${root.path}/link").isSymbolicLink())
        // A directory that merely *contains* a link is not itself a link.
        assertFalse(root.isSymbolicLink())
        assertFalse(outside.isSymbolicLink())
    }

    @Test
    fun atomicWriteStagesASiblingAndLeavesNothingBehind() {
        val dir = ModelFile(PlatformTemp.createDirectory("ws-atomic"))
        val target = ModelFile("${dir.path}/out.txt")
        target.writeBytesAtomic("first".encodeToByteArray())
        assertEquals("first", target.readText())

        // The staging file must be renamed away, not left in the workspace: a
        // leftover would show up in files.list and in the model's view of the
        // directory.
        val leftovers = dir.listFiles().filter { it.name.startsWith(".pending-") }
        assertTrue(leftovers.isEmpty(), "staging files must not survive: $leftovers")
    }

    @Test
    fun atomicWriteReplacesRatherThanAppends() {
        val dir = ModelFile(PlatformTemp.createDirectory("ws-atomic2"))
        val target = ModelFile("${dir.path}/out.txt")
        target.writeBytesAtomic("a considerably longer first payload".encodeToByteArray())
        target.writeBytesAtomic("tiny".encodeToByteArray())
        assertEquals("tiny", target.readText())
    }

    @Test
    fun atomicWriteCreatesParentDirectoriesAndSurvivesMultibyteContent() {
        val dir = ModelFile(PlatformTemp.createDirectory("ws-atomic3"))
        val deep = ModelFile("${dir.path}/x/y/z.txt")
        val text = "moon phase \u00bd \u2014 \u30cb\u30e3\u30fc\u3093 \ud83c\udf19"
        deep.writeBytesAtomic(text.encodeToByteArray())
        assertEquals(text, deep.readText())
    }

    @Test
    fun readingBytesRefusesDirectoriesAndMissingPaths() {
        val dir = ModelFile(PlatformTemp.createDirectory("ws-read"))
        assertNull(ModelFile("${dir.path}/absent.txt").readBytes())
        assertNull(dir.readBytes(), "a directory is not a file")
        assertNull(dir.readText())
    }
}
