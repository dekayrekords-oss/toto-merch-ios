package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.work.ProjectAllocationJournal
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The journal's symlink guards, which need a JVM to set up.
 *
 * Creating a symlink needs `java.nio.file.Files`, which is JVM-only, so these
 * live here rather than in `commonTest` — and that means they run on the JVM
 * only. The iOS `isSymbolicLink` this depends on is compile-verified but not
 * executed anywhere in this project.
 *
 * The original guards symlinks **asymmetrically**, and the asymmetry is
 * deliberate rather than an oversight, so it is pinned here as written:
 *
 *  - `read` refuses a linked *file* but says nothing about the parent
 *    directory. Reading follows the named file and writes nothing, so the only
 *    thing a link could redirect is the source of a read the caller already
 *    asked for.
 *  - `save` refuses a linked file **and** a linked parent. `save` stages a
 *    sibling temp file and renames it into place, so the parent directory is
 *    not incidental: it decides which directory receives the bytes. A linked
 *    parent would move the journal — and the atomic rename with it — out of
 *    the metadata root entirely.
 *
 * All three tests are port-only; the original has no test source set for
 * `core/work` at all.
 */
class ProjectAllocationJournalSymlinkTest {

    /** A valid journal file, so a refusal cannot be explained by its contents. */
    private fun validJournal(): String =
        """{"version":1,"receipts":[{"key":"${"a".repeat(64)}","name":"Launch","kind":"WEBSITE",""" +
            """"at":1,"conversationId":40,"projectId":7,"verified":true}]}"""

    @Test
    fun refusesASymlinkedJournalFileOnRead() {
        val base = PlatformTemp.createDirectory("journal-link")
        val outside = ModelFile("${base}/outside").also { it.mkdirs() }
        val target = ModelFile("${outside.path}/allocations.json")
        target.writeBytesAtomic(validJournal().encodeToByteArray())
        // Proof that the contents are fine: read directly, it parses.
        assertEquals(1, ProjectAllocationJournal(target).read().size)

        val linked = ModelFile("${base}/allocations.json")
        Files.createSymbolicLink(File(linked.path).toPath(), File(target.path).toPath())

        // The same bytes, reached through a link, are refused — so what is being
        // rejected is the link, not the payload.
        assertFailsWith<IllegalStateException> { ProjectAllocationJournal(linked).read() }
    }

    @Test
    fun refusesASymlinkedJournalFileOnSave() {
        val base = PlatformTemp.createDirectory("journal-link2")
        val outside = ModelFile("${base}/outside").also { it.mkdirs() }
        val target = ModelFile("${outside.path}/allocations.json")
        target.writeBytesAtomic("original".encodeToByteArray())

        val linked = ModelFile("${base}/allocations.json")
        Files.createSymbolicLink(File(linked.path).toPath(), File(target.path).toPath())

        assertFailsWith<IllegalStateException> {
            ProjectAllocationJournal(linked).save(
                listOf(ProjectAllocationJournal.Receipt("a".repeat(64), "Launch", "WEBSITE", 1, 40)),
            )
        }
        // The link target is untouched: a refused save wrote nothing anywhere.
        assertEquals("original", target.readText())
    }

    @Test
    fun refusesASymlinkedParentDirectoryOnSave() {
        val base = PlatformTemp.createDirectory("journal-link3")
        val real = ModelFile("${base}/real").also { it.mkdirs() }
        val link = ModelFile("${base}/link")
        Files.createSymbolicLink(File(link.path).toPath(), File(real.path).toPath())

        val journal = ModelFile("${link.path}/allocations.json")
        assertFailsWith<IllegalStateException> {
            ProjectAllocationJournal(journal).save(
                listOf(ProjectAllocationJournal.Receipt("a".repeat(64), "Launch", "WEBSITE", 1, 40)),
            )
        }
        // Nothing was staged in, or renamed into, the directory the link names.
        assertTrue(real.listFiles().isEmpty(), "a refused save must not write through the link")
        assertTrue(link.isSymbolicLink(), "the link must survive the refusal")
    }
}
