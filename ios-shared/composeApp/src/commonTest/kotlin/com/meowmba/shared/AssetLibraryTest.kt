package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.core.PlatformTemp
import com.meowmba.shared.files.AssetAiPlanner
import com.meowmba.shared.files.AssetLibrary
import com.meowmba.shared.files.AssetMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The asset library: the browser's view of the workspace, and the only thing
 * standing between a model-proposed move and the user's files.
 *
 * Ported from `AssetLibraryTest` (20 methods). 14 are here, 1 moved to
 * `jvmTest` because it creates a symlink, and 5 are deferred with a named
 * prerequisite rather than dropped:
 *
 *  - `agentMutationsRequireApproval…`, `commandRoutesAreDeterministic`,
 *    `searchToolReadsActualLibrary`, `agentTextReaderIsBounded…` need the
 *    `file.*` tools and the agent registry/router — the `core/agent` step.
 *  - `mediaSavedInActiveProjectWithoutOverwriting` needs `Workspace`'s
 *    project-aware half, which is gated on `ProjectExecutionContext`.
 *
 * `importsNeverOverwriteAndKeepOriginalStreamBytes` is ported in reshaped form:
 * `AssetLibrary.import` is not ported (it takes a `java.io.InputStream`, see the
 * class KDoc), so the property it pinned — a new destination every time, with
 * the bytes that were handed in — is pinned through `newOutput` instead.
 *
 * Everything runs against a real directory. The class is entirely about what
 * the filesystem does with `..`, symlinks and rename semantics, so a fake
 * filesystem would be testing the fake.
 */
class AssetLibraryTest {

    private class Sandbox(val library: AssetLibrary, val root: ModelFile) {
        /** The original's `library.resolve(path).apply { parentFile!!.mkdirs(); writeText(content) }`. */
        fun file(path: String, content: String = "hello"): ModelFile =
            library.resolve(path).apply {
                parent()!!.mkdirs()
                writeBytesAtomic(content.encodeToByteArray())
            }

        /**
         * The library-relative path of [file], asked of the library rather than
         * computed by string surgery.
         *
         * `resolve` — and therefore `newOutput` — returns a *canonical* path, so
         * on macOS it reads `/private/var/…` while [root] reads `/var/…`.
         * Comparing the two textually fails on exactly the platform alias
         * `AssetLibrary.entry` exists to handle.
         */
        fun relativePath(file: ModelFile): String = library.entry(file).path
    }

    private fun sandbox(): Sandbox {
        val root = ModelFile(PlatformTemp.createDirectory("assets"))
        return Sandbox(AssetLibrary(root), root)
    }

    // ---------------------------------------------------------------- resolve

    @Test
    fun rejectsTraversalHiddenPathsUrlsAndRoot() {
        val s = sandbox()
        // "   " is here for a specific reason: every other entry is refused by
        // the segment or containment checks, but whitespace reaches
        // `require(allowRoot || path.isNotBlank())` and is refused *only* there.
        for (path in listOf("../key", "/etc/passwd", "", "   ", ".asset-trash/payload", "photos/../keys", "https://x/a", "a\\b", "a/./b"))
            assertFailsWith<IllegalArgumentException>("resolve must refuse '$path'") { s.library.resolve(path) }
    }

    /**
     * `Character.isISOControl` and Java's `\p{Cntrl}` are **not** the same
     * predicate, and the original used one in [AssetLibrary.resolve] and the
     * other in `safeName`.
     *
     * `\p{Cntrl}` is exactly `[\x00-\x1F\x7F]`. U+0085 is C1, so the regex
     * *kept* it — confirmed against the JDK, not recalled. This test pins that
     * half; the next one pins the other.
     */
    @Test
    fun safeNameStripsTheCntrlClassNotTheWiderIsoControlSet() {
        assertEquals("a_b", AssetLibrary.safeName("a\u0000b"), "C0 goes")
        assertEquals("a_b", AssetLibrary.safeName("a\u007Fb"), "DEL goes")
        assertEquals("a\u0085b", AssetLibrary.safeName("a\u0085b"), "U+0085 is C1: \\p{Cntrl} does not match it")
    }

    /**
     * The other half of the asymmetry, and the reason it is not merely
     * cosmetic: a name `safeName` lets through is still refused by `resolve`.
     *
     * If `safeName` were "fixed" to strip C1 as well, this test would still
     * pass — but `safeNameStripsTheCntrlClassNotTheWiderIsoControlSet` would
     * fail, which is the point of pinning both ends.
     */
    @Test
    fun resolveRejectsC1ControlsThatSafeNameWouldKeep() {
        val s = sandbox()
        assertFailsWith<IllegalArgumentException> { s.library.resolve("a\u0085b") }
        // ...and the C0 half is refused by both.
        assertFailsWith<IllegalArgumentException> { s.library.resolve("a\u0000b") }
    }

    @Test
    fun safeNameStripsEveryPathSeparatorAndReservedCharacter() {
        // The whole set the original's character class held, one character at a
        // time. A single miss is a path separator — or a character Windows
        // treats as one.
        for (c in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|'))
            assertEquals("a_b", AssetLibrary.safeName("a${c}b"), "safeName must strip '$c'")
        // U+001C is C0 and is also `Character.isWhitespace`, so it goes through
        // the control branch rather than the trim.
        assertEquals("a_b", AssetLibrary.safeName("a\u001Cb"))
    }

    @Test
    fun safeNameFallsBackToAssetWhenNothingUsableRemains() {
        assertEquals("asset", AssetLibrary.safeName(""))
        assertEquals("asset", AssetLibrary.safeName("   "))
        // `.trim('.').take(160).ifBlank { "asset" }` — the take happens *before*
        // the blank test, so a run of dots collapses to the fallback.
        assertEquals("asset", AssetLibrary.safeName("..."))
        assertEquals("y", AssetLibrary.safeName("...y..."))
        assertEquals(160, AssetLibrary.safeName("x".repeat(200)).length, "take(160) is a bound, not a trim")
    }

    /**
     * `entry()` must never report a path the browser cannot hand back.
     *
     * The original's comment explains why this needs care: `resolve` returns a
     * *canonical* file, and a platform root alias (macOS `/var` →
     * `/private/var`, Android `/data/data`) would otherwise turn an ordinary
     * path into `../…`. The round trip below is the contract.
     *
     * On macOS this test genuinely exercises the alias branch — the temp root
     * is handed back as `/var/…` while its canonical form is `/private/var/…`.
     * On a platform where the two agree it still pins that the path is
     * root-relative rather than absolute.
     */
    @Test
    fun entryAlwaysReportsAPathThatResolvesBackToTheSameFile() {
        val s = sandbox()
        for (path in listOf("a.txt", "photos/cat.png", "documents/deep/nested.txt")) {
            val file = s.file(path)
            val item = s.library.entry(file)
            assertEquals(path, item.path)
            assertFalse(item.path.startsWith(".."), "entry must not report an escape: ${item.path}")
            assertEquals(file.path, s.library.resolve(item.path).path)
        }
    }

    // ------------------------------------------------------------------ scan

    @Test
    fun scanCategorizesAndReportsLimit() {
        val s = sandbox()
        s.file("cat.png"); s.file("movie.mp4"); s.file("note.txt")
        assertEquals(setOf("Photos", "Videos", "Documents"), s.library.scan().entries.map { it.category }.toSet())
        assertTrue(s.library.scan(2).truncated)
        assertEquals(2, s.library.scan(2).entries.size)
    }

    @Test
    fun scanSkipsHiddenEntriesAndNeverDescendsIntoTrash() {
        val s = sandbox()
        s.file("keep.txt")
        s.library.trash(s.library.entry(s.file("gone.txt")))
        // `.asset-trash` starts with a dot, so the walk never enters it — which
        // is what keeps a trashed file from reappearing in the browser.
        assertEquals(listOf("keep.txt"), s.library.scan().entries.map { it.path })
    }

    @Test
    fun localSearchReturnsRealPaths() {
        val s = sandbox()
        s.file("photos/garden-cat.jpg"); s.file("documents/taxes.pdf")
        assertEquals("photos/garden-cat.jpg", s.library.search("garden photos").entries.single().path)
    }

    // -------------------------------------------------------------- newOutput

    /**
     * The reshaped `importsNeverOverwriteAndKeepOriginalStreamBytes`.
     *
     * The original called `library.import(...)` twice and checked the two
     * destinations differed and each held the bytes it was given. `import` is
     * deferred (it needs a platform stream), so what is pinned here is the half
     * `Workspace.newAsset` actually uses: `newOutput` reserves a *real*, empty
     * file at a fresh path, every time.
     */
    @Test
    fun newOutputReservesADistinctRealFileEveryTime() {
        val s = sandbox()
        val first = s.library.newOutput("documents", "report.txt")
        val second = s.library.newOutput("documents", "report.txt")
        assertNotEquals(first.path, second.path)
        // `createNewFile()` left a real file behind, so "reserved" meant
        // something. The port's check-then-create must too.
        assertTrue(first.exists() && second.exists(), "a reserved output must exist")
        assertEquals(0L, first.length())
        assertEquals("documents", s.relativePath(first.parent()!!))
        assertEquals("report.txt", first.name.substringAfter('_'))
        assertTrue(first.name.substringBefore('_').matches(Regex("[a-f0-9]{8}")), first.name)
    }

    @Test
    fun newOutputCreatesTheFolderAndSanitizesTheName() {
        val s = sandbox()
        val out = s.library.newOutput("documents", "a/b:c.txt")
        // The '/' in the name was replaced, so the output did not become a
        // nested path — the folder is still the one that was asked for.
        assertEquals("documents", s.relativePath(out.parent()!!))
        assertEquals("a_b_c.txt", out.name.substringAfter('_'))
    }

    // ------------------------------------------------------------- createFolder

    @Test
    fun createFolderMakesParentsAndRefusesToRedefine() {
        val s = sandbox()
        assertTrue(s.library.createFolder("a/b/c").isDirectory())
        assertFailsWith<IllegalArgumentException> { s.library.createFolder("a/b/c") }
    }

    @Test
    fun ensureFoldersCreatesTheStandardSet() {
        val s = sandbox()
        s.library.ensureFolders()
        for (folder in AssetLibrary.folders)
            assertTrue(s.library.resolve(folder).isDirectory(), folder)
        assertEquals(
            AssetLibrary.folders.toSet(),
            s.library.scan().entries.filter { it.folder }.map { it.path }.toSet(),
        )
    }

    // ------------------------------------------------------------------ move

    @Test
    fun moveRejectsChangedSourceAndOverwrite() {
        val s = sandbox()
        val source = s.file("a.txt"); s.file("b.txt", "keep")
        val entry = s.library.entry(source)
        assertFailsWith<IllegalArgumentException> { s.library.move(AssetMove(entry, "b.txt")) }
        source.writeBytesAtomic("changed".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { s.library.move(AssetMove(entry, "c.txt")) }
        assertEquals("keep", s.library.resolve("b.txt").readText())
    }

    @Test
    fun moveCreatesDestinationFolders() {
        val s = sandbox()
        val source = s.file("a.txt")
        s.library.move(AssetMove(s.library.entry(source), "documents/topic/a.txt"))
        assertFalse(source.exists())
        assertEquals("hello", s.library.resolve("documents/topic/a.txt").readText())
    }

    /**
     * The third appearance of the `File.equals` trap in this port.
     *
     * The original wrote `require(source != target && !target.exists())`, where
     * `java.io.File.equals` is path-based. `ModelFile` does not override
     * `equals`, so a literal translation would make that clause always true.
     *
     * What this test pins is the *observable* refusal. Note that
     * `!target.exists()` alone already produces it — a self-move always has an
     * existing target — so the path comparison is redundant rather than
     * load-bearing, and that is true of the original too. The port keeps the
     * clause because it states the intent and matches the original line for
     * line; the mutation log records that deleting it fails no test.
     */
    @Test
    fun movingAFileOntoItselfIsRefused() {
        val s = sandbox()
        val source = s.file("a.txt")
        assertFailsWith<IllegalArgumentException> { s.library.move(AssetMove(s.library.entry(source), "a.txt")) }
        assertEquals("hello", source.readText())
    }

    /**
     * The other half of `unchanged`'s guard.
     *
     * `unchanged` checks the byte count *and* the modification time. The
     * `moveRejectsChangedSourceAndOverwrite` case changes both, so on its own it
     * would not notice the mtime check going missing. This constructs an entry
     * whose recorded mtime does not match the file — which is exactly the state
     * after an edit that preserved the size — rather than racing the clock for
     * a same-length rewrite, so it cannot flake.
     */
    @Test
    fun unchangedRejectsAnEditThatKeptTheByteCount() {
        val s = sandbox()
        val source = s.file("a.txt", "hello")
        val honest = s.library.entry(source)
        assertEquals(honest.bytes, source.length())

        val stale = honest.copy(modified = honest.modified + 1)
        assertFailsWith<IllegalArgumentException> { s.library.move(AssetMove(stale, "b.txt")) }
        assertEquals("hello", source.readText())
        assertFalse(s.library.resolve("b.txt").exists(), "the refused move must not have happened")
    }

    /**
     * The byte-count half of the same guard, pinned the same constructed way and
     * for the same reason: any real edit moves the modification time too, so the
     * two checks cannot be separated through the filesystem. Constructing the
     * mismatch is what makes each one individually observable — without this,
     * deleting the byte count fails nothing (mutation M19).
     */
    @Test
    fun unchangedRejectsAReplacementThatKeptTheTimestamp() {
        val s = sandbox()
        val source = s.file("a.txt", "hello")
        val honest = s.library.entry(source)

        // Right timestamp, wrong length: the state after a file is swapped for a
        // different one with its mtime preserved (`cp -p`, an unzip, a restore
        // from a backup). That is the case the byte count is the only guard for.
        val stale = honest.copy(bytes = honest.bytes + 1)
        assertFailsWith<IllegalArgumentException> { s.library.move(AssetMove(stale, "b.txt")) }
        assertEquals("hello", source.readText())
    }

    // --------------------------------------------------------------- cleanup

    @Test
    fun cleanupComparesBytesAndNeverChangesFiles() {
        val s = sandbox()
        s.file("a.txt", "same"); s.file("b.txt", "same"); s.file("c.txt", "diff"); s.file("empty.txt", "")
        val (candidates, _) = s.library.cleanup()
        assertEquals(setOf("b.txt", "empty.txt"), candidates.map { it.asset.path }.toSet())
        assertEquals(4, s.library.scan().entries.size)
    }

    // ----------------------------------------------------------------- trash

    @Test
    fun trashRestoreRoundTripDoesNotChangeBytes() {
        val s = sandbox()
        val original = s.file("documents/test.txt")
        val id = s.library.trash(s.library.entry(original))
        assertFalse(original.exists())
        assertTrue(s.library.scan().entries.none { !it.folder })
        assertEquals("documents/test.txt", s.library.trashItems().single().original)
        s.library.restore(id)
        assertEquals("hello", original.readText())
        assertTrue(s.library.trashItems().isEmpty())
    }

    @Test
    fun restoreNeverOverwrites() {
        val s = sandbox()
        val original = s.file("test.txt")
        val id = s.library.trash(s.library.entry(original))
        original.writeBytesAtomic("new".encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { s.library.restore(id) }
        assertEquals("new", original.readText())
        assertEquals(1, s.library.trashItems().size)
    }

    @Test
    fun purgeOnlyDeletesTheExactTrashItem() {
        val s = sandbox()
        val keep = s.file("keep.txt", "safe")
        val id = s.library.trash(s.library.entry(s.file("delete.txt")))
        assertFailsWith<IllegalArgumentException> { s.library.purge("../keep.txt") }
        s.library.purge(id)
        assertTrue(s.library.trashItems().isEmpty())
        assertEquals("safe", keep.readText())
    }

    /**
     * `trashDir`'s `[a-f0-9-]{36}` regex is the path-traversal guard for the
     * trash directory, and it is only usable as one because the id is the full
     * UUID. This is why `PlatformId.uuid()` was added to the seam instead of
     * widening the regex to fit `shortId()`.
     */
    @Test
    fun aTrashIdIsAFullUuidBecauseTheGuardRegexIsTheTraversalCheck() {
        val s = sandbox()
        val id = s.library.trash(s.library.entry(s.file("a.txt")))
        assertEquals(36, id.length)
        assertTrue(id.matches(Regex("[a-f0-9-]{36}")), id)
    }

    /**
     * A trash directory is on disk and writable by anything that can write the
     * library, so `trashItems()` must not trust what it finds there.
     *
     * Port-only: the original relied on the same `resolve(path)` call but had no
     * test for it, and `entry.properties` made the malformed cases awkward to
     * construct. With a JSON metadata file they are three lines each.
     */
    @Test
    fun trashEntriesWithTamperedOrUnreadableMetadataAreIgnored() {
        val s = sandbox()
        val cases = listOf(
            "{\"path\":\"../escape.txt\",\"date\":1}" to "an escaping restore path",
            "not json" to "unparseable metadata",
            "{\"date\":1}" to "a missing restore path",
        )
        var index = 0
        for ((metadata, why) in cases) {
            val id = "11111111-2222-3333-4444-55555555555$index"
            val dir = ModelFile("${s.root.path}/${AssetLibrary.TRASH}/$id").also { it.mkdirs() }
            ModelFile("${dir.path}/payload").writeBytesAtomic("x".encodeToByteArray())
            ModelFile("${dir.path}/entry.json").writeBytesAtomic(metadata.encodeToByteArray())
            assertTrue(s.library.trashItems().isEmpty(), "must ignore $why")
            assertFailsWith<IllegalStateException>("restore must refuse $why") { s.library.restore(id) }
            index++
        }
    }

    /**
     * `trashDir`'s `[a-f0-9-]{36}` regex is what stops an arbitrary directory
     * name from being used as a path, so it needs a test that names it.
     *
     * The entry below is otherwise perfectly well formed — a real payload, valid
     * JSON, a restore path that resolves — so the *only* thing that can reject
     * it is the id check. Without that check `trashItems()` would list it.
     */
    @Test
    fun aTrashDirectoryWithANonUuidNameIsIgnored() {
        val s = sandbox()
        s.file("a.txt")
        val dir = ModelFile("${s.root.path}/${AssetLibrary.TRASH}/not-a-uuid").also { it.mkdirs() }
        ModelFile("${dir.path}/payload").writeBytesAtomic("x".encodeToByteArray())
        ModelFile("${dir.path}/entry.json").writeBytesAtomic("{\"path\":\"a.txt\",\"date\":1}".encodeToByteArray())

        assertTrue(s.library.trashItems().isEmpty(), "a non-UUID trash directory must be ignored")
        assertFailsWith<IllegalStateException> { s.library.restore("not-a-uuid") }
        assertFailsWith<IllegalArgumentException> { s.library.purge("not-a-uuid") }
    }

    /**
     * `purge` must refuse an id that names nothing the trash UI ever showed.
     *
     * This one is pinned by *effect* rather than by exception type, and that is
     * deliberate: both of `purge`'s guards — the `trashItems().any { … }` check
     * and `trashDir`'s id regex — throw for a malformed id, and the payload
     * `check` throws the same `IllegalStateException` as the `require`, so no
     * assertion on the exception can tell them apart (mutation M30 survived on
     * exactly that basis). The observable difference is the deletion: without
     * the `any` check, `purge` happily deletes the payload of an entry whose
     * metadata is unreadable — permanently destroying a file the user was never
     * shown and therefore could not have consented to losing.
     */
    @Test
    fun purgeRefusesAnEntryTheTrashListDoesNotShow() {
        val s = sandbox()
        s.file("a.txt")
        val id = "11111111-2222-3333-4444-555555555555"
        val dir = ModelFile("${s.root.path}/${AssetLibrary.TRASH}/$id").also { it.mkdirs() }
        ModelFile("${dir.path}/payload").writeBytesAtomic("x".encodeToByteArray())
        ModelFile("${dir.path}/entry.json").writeBytesAtomic("not json".encodeToByteArray())
        assertTrue(s.library.trashItems().isEmpty(), "unreadable metadata means it is not listed")

        assertFailsWith<IllegalArgumentException> { s.library.purge(id) }
        assertTrue(dir.exists(), "an unlisted entry must not be deleted")
        assertTrue(ModelFile("${dir.path}/payload").exists(), "its payload must survive")
    }

    // ---------------------------------------------------------- AssetAiPlanner

    @Test
    fun aiSearchRejectsInventedIds() {
        val s = sandbox()
        val entries = listOf(s.library.entry(s.file("test.txt")))
        assertFailsWith<IllegalArgumentException> { AssetAiPlanner.matches("{\"matches\":[9]}", entries) }
        assertEquals(entries, AssetAiPlanner.matches("{\"matches\":[0]}", entries))
    }

    @Test
    fun aiOrganizationCannotEscapeOverwriteOrChangeType() {
        val s = sandbox()
        val entries = listOf(s.library.entry(s.file("test.txt")))
        s.file("existing.txt")
        for (destination in listOf("../secret.txt", "existing.txt", "documents/test.apk", ".asset-trash/test.txt"))
            assertFailsWith<IllegalArgumentException>("destination '$destination'") {
                AssetAiPlanner.moves("{\"moves\":[{\"id\":0,\"destination\":\"$destination\"}]}", entries, s.library)
            }
        assertEquals(
            "documents/test.txt",
            AssetAiPlanner.moves("{\"moves\":[{\"id\":0,\"destination\":\"documents/test.txt\"}]}", entries, s.library).single().destination,
        )
    }

    @Test
    fun aiNeverMovesProjectDependencies() {
        val s = sandbox()
        val entries = listOf(s.library.entry(s.file("projects/project-7/main.txt")))
        assertFailsWith<IllegalArgumentException> {
            AssetAiPlanner.moves("{\"moves\":[{\"id\":0,\"destination\":\"documents/main.txt\"}]}", entries, s.library)
        }
    }

    @Test
    fun aiPromptContainsMetadataNotContents() {
        val s = sandbox()
        val entry = s.library.entry(s.file("letter.txt", "private content"))
        val prompt = AssetAiPlanner.prompt("find letter", listOf(entry), false)
        assertTrue(prompt.contains("letter.txt"))
        assertFalse(prompt.contains("private content"))
    }
}
