package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.files.AssetLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The parts of the asset library that need a JVM to set up.
 *
 * A symlink is the one escape a purely lexical check cannot catch: `escape/a.txt`
 * looks like an ordinary relative path, and only resolving the link reveals that
 * it leaves the library. Creating one needs `java.nio.file.Files`, which is
 * JVM-only, so this lives here rather than in `commonTest` — and that means it
 * runs on the JVM only, not for iOS. Saying so is the honest thing; the iOS
 * `canonicalPath` and `isSymbolicLink` this depends on are compile-verified but
 * not executed anywhere in this project.
 *
 * `neverFollowsSymlinks` is the original's one test here, ported verbatim. The
 * second test is port-only: `trashDir` has its own symlink check, and a guard
 * that no test names is a guard nobody will notice losing.
 */
class AssetLibrarySymlinkTest {

    private fun libraryIn(base: String): AssetLibrary =
        AssetLibrary(ModelFile("${base}/workspace"))

    @Test
    fun neverFollowsSymlinks() {
        val base = PlatformTemp.createDirectory("assets-link")
        val outside = ModelFile("${base}/outside").also { it.mkdirs() }
        ModelFile("${outside.path}/a.txt").writeBytesAtomic("secret".encodeToByteArray())
        val library = libraryIn(base)

        Files.createSymbolicLink(
            File("${library.root.path}/escape").toPath(),
            File(outside.path).toPath(),
        )

        // The link itself is inside the library, so a lexical check passes it.
        // Only canonicalisation reveals that escape/a.txt is outside.
        assertFailsWith<IllegalArgumentException> { library.resolve("escape/a.txt") }
        // And a scan must not report it either, so the browser never shows a
        // file the user cannot actually reach.
        assertTrue(library.scan().entries.isEmpty(), "a scan must not follow a link out of the library")
    }

    @Test
    fun aSymlinkedTrashDirectoryIsRefusedBeforeItIsUsedAsAPath() {
        val base = PlatformTemp.createDirectory("assets-link2")
        val outside = ModelFile("${base}/outside").also { it.mkdirs() }
        val library = libraryIn(base)
        ModelFile("${library.root.path}/a.txt").writeBytesAtomic("hello".encodeToByteArray())

        // `trashDir` guards the trash root as well as the id. Without the
        // symlink check, trashing a file would move it into whatever the link
        // points at -- i.e. out of the library, silently.
        Files.createSymbolicLink(
            File("${library.root.path}/${AssetLibrary.TRASH}").toPath(),
            File(outside.path).toPath(),
        )

        assertFailsWith<IllegalArgumentException> { library.trash(library.entry(ModelFile("${library.root.path}/a.txt"))) }
        assertTrue(library.trashItems().isEmpty())
        assertEquals(0, outside.listFiles().size, "nothing may have been moved outside")
    }

    /**
     * The per-segment symlink check, as distinct from canonical containment.
     *
     * `neverFollowsSymlinks` does not pin this check on its own: a link that
     * leaves the root is refused by the containment check as well, so deleting
     * the `isSymbolicLink` line fails nothing (mutation M14 survived). The link
     * below points *back inside* the library, so containment would accept it and
     * only the per-segment check refuses it.
     *
     * The policy the class states is "linked paths are not supported", not
     * "paths that happen to resolve inside are fine", and this is the test that
     * says which of the two is meant.
     */
    @Test
    fun aSymlinkIsRefusedEvenWhenItPointsBackInsideTheLibrary() {
        val base = PlatformTemp.createDirectory("assets-link3")
        val library = libraryIn(base)
        val real = ModelFile("${library.root.path}/real").also { it.mkdirs() }
        ModelFile("${real.path}/a.txt").writeBytesAtomic("hello".encodeToByteArray())

        Files.createSymbolicLink(
            File("${library.root.path}/alias").toPath(),
            File(real.path).toPath(),
        )

        assertFailsWith<IllegalArgumentException> { library.resolve("alias/a.txt") }
        // The refusal is about the link, not the file: the real path still works.
        assertEquals("hello", library.resolve("real/a.txt").readText())
    }
}
