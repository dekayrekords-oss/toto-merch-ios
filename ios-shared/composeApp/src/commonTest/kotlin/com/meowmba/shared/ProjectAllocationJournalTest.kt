package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.work.ProjectAllocationJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The project allocation journal: the write-ahead record that lets an
 * interrupted project insert be reconciled instead of repeated.
 *
 * **Every test here is port-only.** Upstream `core/work` has no test source set
 * — nothing in the original repository constructs [ProjectAllocationJournal],
 * so its eleven guards shipped unexercised. They were written from the contract
 * the guards encode, and the mutation pass for this file is the only thing
 * standing behind them (see the class KDoc).
 *
 * The guards, and where each is pinned:
 *
 * | # | guard                                              | test |
 * |---|----------------------------------------------------|------|
 * | R1 | journal path is a symlink (read)                   | `jvmTest` |
 * | R2 | journal absent -> empty history                    | `anAbsentJournalReadsAsEmpty` |
 * | R3 | not a file, or over 64 KB                          | `refusesAnOversizedFile`, `refusesADirectoryInPlaceOfTheJournal` |
 * | R4 | unknown or missing version                         | `refusesAnUnknownVersion`, `refusesAMissingVersion` |
 * | R5 | per-record validity (key/name/conversation/project/kind) | six tests |
 * | R6 | at most 16, distinct keys, distinct conversations  | three tests |
 * | S1 | `save` refuses more than 16                        | `refusesToSaveMoreThanSixteenRecords` |
 * | S2 | no parent directory                                | `refusesAParentlessPath` |
 * | S3 | journal or parent is a symlink (save)              | `jvmTest` |
 * | S4 | parent cannot be created                           | `refusesAnUnwritableParent` |
 * | S5 | encoded payload over 64 KB                         | `refusesAnOversizedPayload` |
 *
 * Runs against a real directory, like `AssetLibraryTest`: this class is
 * entirely about what the filesystem does with symlinks, lengths and rename
 * semantics, so a fake would be testing the fake.
 */
class ProjectAllocationJournalTest {

    // ------------------------------------------------------------------ setup

    private class Dir(val root: ModelFile) {
        val journal: ModelFile = ModelFile("${root.path}/allocations.json")

        fun subject() = ProjectAllocationJournal(journal)

        /** Writes [text] verbatim, bypassing `save` — for files the class did not write. */
        fun writeRaw(text: String) = journal.writeBytesAtomic(text.encodeToByteArray())

        /** Names present in the journal's directory, sorted. */
        fun entries(): List<String> = journal.parent()!!.listFiles().map { it.name }.sorted()
    }

    private fun dir(): Dir = Dir(ModelFile(PlatformTemp.createDirectory("allocations")))

    /** A syntactically valid 64-hex key; `n` makes it distinct. */
    private fun key(n: Int): String = n.toString(16).padStart(64, '0')

    private fun receipt(
        n: Int,
        name: String = "Project $n",
        kind: String = "OTHER",
        conversationId: Long = n.toLong(),
        projectId: Long = 0,
        verified: Boolean = false,
    ) = ProjectAllocationJournal.Receipt(
        key(n), name, kind, at = n.toLong(), conversationId = conversationId,
        projectId = projectId, verified = verified,
    )

    /** One record as raw JSON, so malformed files can be constructed field by field. */
    private fun receiptJson(
        key: String,
        name: String = "Project",
        kind: String = "OTHER",
        at: Long = 1,
        conversationId: Long = 1,
        projectId: Long = 0,
        verified: Boolean = false,
    ) = """{"key":"$key","name":"$name","kind":"$kind","at":$at,""" +
        """"conversationId":$conversationId,"projectId":$projectId,"verified":$verified}"""

    private fun journalJson(vararg records: String) =
        """{"version":1,"receipts":[${records.joinToString(",")}]}"""

    // -------------------------------------------------------------- R2, absent

    @Test
    fun anAbsentJournalReadsAsEmpty() {
        // Not an error: a first launch has no history, and `read` must not
        // invent a reason to block project creation.
        assertEquals(emptyList(), dir().subject().read())
    }

    // ------------------------------------------------------------ round trip

    @Test
    fun roundTripsEveryReceiptField() {
        val d = dir()
        val saved = listOf(
            receipt(1, name = "Launch", kind = "WEBSITE", conversationId = 40, projectId = 7, verified = true),
            receipt(2, name = "Vacation", kind = "PHOTO", conversationId = 41, projectId = 0),
        )
        d.subject().save(saved)
        assertEquals(saved, d.subject().read())
    }

    @Test
    fun aReceiptDefaultsToAnUnallocatedUnverifiedProject() {
        // `ProjectCreationService` writes the reservation with the five-argument
        // form, before any Room row exists — so both defaults are load-bearing:
        // `projectId = 0` is what tells a later launch the insert may still be
        // pending, and `verified = false` is what says it has not been
        // reconciled against the database yet. The `receipt()` helper below
        // passes both explicitly, so they need pinning from this side.
        val fresh = ProjectAllocationJournal.Receipt(key(1), "Launch", "WEBSITE", at = 1, conversationId = 40)
        assertEquals(0L, fresh.projectId)
        assertEquals(false, fresh.verified)
        // And the defaults must survive a round trip, not just construction.
        val d = dir()
        d.subject().save(listOf(fresh))
        assertEquals(listOf(fresh), d.subject().read())
    }

    @Test
    fun savingNoReceiptsClearsTheJournal() {
        // `save(emptyList())` is how a consumed reservation is retired, and the
        // result must be indistinguishable from never having written one.
        val d = dir()
        d.subject().save(listOf(receipt(1)))
        d.subject().save(emptyList())
        assertEquals(emptyList(), d.subject().read())
        assertTrue(d.journal.exists())
    }

    @Test
    fun acceptsExactlySixteenRecords() {
        val d = dir()
        val saved = (1..16).map { receipt(it) }
        d.subject().save(saved)
        assertEquals(saved, d.subject().read())
    }

    @Test
    fun createsMissingParentDirectoriesOnSave() {
        // S4's positive half: the directory does not exist yet, so `mkdirs()`
        // is what makes the save succeed rather than the `isDirectory()` arm.
        val root = ModelFile(PlatformTemp.createDirectory("allocations"))
        val nested = ModelFile("${root.path}/deeper/still")
        val journal = ModelFile("${nested.path}/allocations.json")
        ProjectAllocationJournal(journal).save(listOf(receipt(1)))
        assertEquals(listOf(receipt(1)), ProjectAllocationJournal(journal).read())
    }

    @Test
    fun overwritesThePreviousJournalWholesale() {
        val d = dir()
        d.subject().save(listOf(receipt(1), receipt(2)))
        d.subject().save(listOf(receipt(3)))
        // Exactly the second list — not a merge, and no residue of the first.
        assertEquals(listOf(receipt(3)), d.subject().read())
    }

    @Test
    fun leavesNoPendingFileBehind() {
        // The original stages `.allocation-*.pending` and deletes it in a
        // `finally`; `writeBytesAtomic` is that same sequence. A leftover temp
        // file would mean the cleanup was skipped, and it would accumulate in
        // the metadata directory one interrupted save at a time.
        val d = dir()
        d.subject().save(listOf(receipt(1)))
        d.subject().save(listOf(receipt(2)))
        assertEquals(listOf("allocations.json"), d.entries())
    }

    // ------------------------------------------------------------------- R3

    @Test
    fun refusesAnOversizedFile() {
        val d = dir()
        d.writeRaw("x".repeat(64_001))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesADirectoryInPlaceOfTheJournal() {
        val d = dir()
        assertTrue(d.journal.mkdirs())
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    // ------------------------------------------------------------------- R4

    @Test
    fun refusesAnUnknownVersion() {
        val d = dir()
        d.writeRaw("""{"version":2,"receipts":[]}""")
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesAMissingVersion() {
        val d = dir()
        d.writeRaw("""{"receipts":[]}""")
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    // ------------------------------------------------------------------- R5
    //
    // Each of these is reachable only through a file this class did not write:
    // `save` performs no field validation, so a hand-edited or older journal is
    // the only way in. That asymmetry is deliberate in the original — `save` is
    // fed receipts the caller just built, `read` is fed whatever is on disk.

    @Test
    fun refusesAMalformedKey() {
        val d = dir()
        for (bad in listOf("", "abc", "A".repeat(64), "f".repeat(63), "g".repeat(64))) {
            d.writeRaw(journalJson(receiptJson(bad)))
            assertFailsWith<IllegalStateException>("key '$bad' must be refused") { d.subject().read() }
        }
    }

    @Test
    fun refusesABlankName() {
        val d = dir()
        d.writeRaw(journalJson(receiptJson(key(1), name = "   ")))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesAnOverlongName() {
        val d = dir()
        d.writeRaw(journalJson(receiptJson(key(1), name = "n".repeat(101))))
        assertFailsWith<IllegalStateException> { d.subject().read() }
        // 100 is the boundary and must pass, or every real name would be capped
        // one character below the documented limit.
        d.writeRaw(journalJson(receiptJson(key(1), name = "n".repeat(100))))
        assertEquals(1, d.subject().read().size)
    }

    @Test
    fun refusesANonPositiveConversationId() {
        val d = dir()
        for (bad in listOf(0L, -1L)) {
            d.writeRaw(journalJson(receiptJson(key(1), conversationId = bad)))
            assertFailsWith<IllegalStateException>("conversationId $bad must be refused") { d.subject().read() }
        }
    }

    @Test
    fun refusesANegativeProjectId() {
        val d = dir()
        d.writeRaw(journalJson(receiptJson(key(1), projectId = -1)))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesAnUnknownKind() {
        val d = dir()
        // 0 is the boundary on the other side: an unallocated receipt is legal.
        for (bad in listOf("", "code", "PROJECT", "OTHER ")) {
            d.writeRaw(journalJson(receiptJson(key(1), kind = bad)))
            assertFailsWith<IllegalStateException>("kind '$bad' must be refused") { d.subject().read() }
        }
        d.writeRaw(journalJson(receiptJson(key(1), kind = "OTHER", projectId = 0)))
        assertEquals(1, d.subject().read().size)
    }

    // ------------------------------------------------------------------- R6

    @Test
    fun refusesMoreThanSixteenRecords() {
        val d = dir()
        d.writeRaw(journalJson(*(1..17).map { receiptJson(key(it), conversationId = it.toLong()) }.toTypedArray()))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesDuplicateKeys() {
        // Distinct conversations, one key repeated: `ProjectCreationService`
        // finds a receipt by key, so a duplicate makes the lookup ambiguous.
        val d = dir()
        d.writeRaw(journalJson(receiptJson(key(1), conversationId = 1), receiptJson(key(1), conversationId = 2)))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    @Test
    fun refusesDuplicateConversationIds() {
        // The mirror image: distinct keys, one conversation. This is the shape
        // that would let two projects claim the same conversation.
        val d = dir()
        d.writeRaw(journalJson(receiptJson(key(1), conversationId = 9), receiptJson(key(2), conversationId = 9)))
        assertFailsWith<IllegalStateException> { d.subject().read() }
    }

    // ------------------------------------------------------------------- S1

    @Test
    fun refusesToSaveMoreThanSixteenRecords() {
        val d = dir()
        assertFailsWith<IllegalStateException> { d.subject().save((1..17).map { receipt(it) }) }
        // Refused before anything was written, so the previous history survives.
        assertTrue(!d.journal.exists())
    }

    // ------------------------------------------------------------------- S2

    @Test
    fun refusesAParentlessPath() {
        // The original's `checkNotNull(file.parentFile)`. With no parent there
        // is no directory to stage the temp file in, so the atomic write could
        // not happen even if the JSON were built.
        assertFailsWith<IllegalStateException> { ProjectAllocationJournal(ModelFile("")).save(emptyList()) }
    }

    // ------------------------------------------------------------------- S4

    @Test
    fun refusesAnUnwritableParent() {
        // The parent path is a regular file, so `mkdirs()` fails and
        // `isDirectory()` is false. Without the guard the failure would surface
        // much later as an IOException from the temp-file staging, with nothing
        // telling the user their projects directory is the problem.
        val d = dir()
        val blocked = ModelFile("${d.root.path}/blocked")
        blocked.writeBytesAtomic("x".encodeToByteArray())
        assertTrue(blocked.isFile(), "the blocker must be a regular file, not a directory")
        val journal = ModelFile("${blocked.path}/allocations.json")
        assertFailsWith<IllegalStateException> { ProjectAllocationJournal(journal).save(listOf(receipt(1))) }
    }

    // ------------------------------------------------------------------- S5

    @Test
    fun refusesAnOversizedPayload() {
        // 16 records — inside S1's limit — but 10 000 characters of name each.
        // `save` does not validate names, so the size guard is the only thing
        // that stops a journal too large for `read` to accept being written.
        val d = dir()
        val fat = (1..16).map { receipt(it, name = "n".repeat(10_000)) }
        assertFailsWith<IllegalStateException> { d.subject().save(fat) }
    }
}
