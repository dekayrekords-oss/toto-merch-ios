package com.meowmba.shared

import com.meowmba.shared.core.AppStorage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real, filesystem-backed half of [AppStorage.writeTextAtomic].
 *
 * This lives in `jvmTest` because it cannot be checked through
 * `MemoryTextStorage`: atomicity and fsync are properties of the storage
 * device, and a map has neither. It runs against the actual JVM actual, which
 * writes to `~/.meowmba/` — hence the clearly-namespaced name and the cleanup.
 *
 * What it does *not* prove: that a power loss is survivable. That would need
 * fault injection below the filesystem. It does prove the observable contract —
 * the bytes land, the staging file does not survive, and a rewrite replaces
 * rather than appends.
 */
class AppStorageAtomicWriteTest {

    private val name = "__atomic_write_test.json"

    @AfterTest
    fun cleanUp() {
        AppStorage.delete(name)
        File(AppStorage.location(), "$name.pending").delete()
    }

    @Test
    fun atomicWriteRoundTripsThroughReadText() {
        AppStorage.writeTextAtomic(name, """{"hello":"world"}""")
        assertEquals("""{"hello":"world"}""", AppStorage.readText(name))
    }

    @Test
    fun theStagingFileDoesNotSurviveASuccessfulWrite() {
        // If `.pending` were left behind, the rename either did not happen or a
        // failed write was not cleaned up -- both would silently double the
        // store's footprint and confuse a later reader.
        AppStorage.writeTextAtomic(name, "first")
        assertFalse(
            File(AppStorage.location(), "$name.pending").exists(),
            "the .pending staging file must be renamed away, not left behind",
        )
    }

    @Test
    fun aSecondWriteReplacesRatherThanAppends() {
        AppStorage.writeTextAtomic(name, "short")
        AppStorage.writeTextAtomic(name, "a considerably longer second payload")
        assertEquals("a considerably longer second payload", AppStorage.readText(name))
        AppStorage.writeTextAtomic(name, "tiny")
        assertEquals("tiny", AppStorage.readText(name))
    }

    @Test
    fun atomicWriteHandlesEmptyAndMultibyteContent() {
        AppStorage.writeTextAtomic(name, "")
        assertEquals("", AppStorage.readText(name))
        // Multi-byte characters must survive the byte-level copy, not just ASCII.
        val text = "moon phase \u00bd \u2014 \u30cb\u30e3\u30fc\u3093 \ud83c\udf19"
        AppStorage.writeTextAtomic(name, text)
        assertEquals(text, AppStorage.readText(name))
    }

    @Test
    fun aMissingNameReadsAsNull() {
        assertNull(AppStorage.readText("__definitely_absent_${name}"))
    }

    @Test
    fun atomicWriteWorksOnTheFirstWriteWhenNoPreviousFileExists() {
        // The rename target does not exist yet; REPLACE_EXISTING must not be
        // required for that case.
        assertNull(AppStorage.readText(name))
        AppStorage.writeTextAtomic(name, "created")
        assertEquals("created", AppStorage.readText(name))
        assertTrue(File(AppStorage.location(), name).isFile)
    }
}
