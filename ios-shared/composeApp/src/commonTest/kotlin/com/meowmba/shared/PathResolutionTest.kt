package com.meowmba.shared

import com.meowmba.shared.agent.appendCollapsing
import com.meowmba.shared.agent.collapseLexically
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The lexical half of path canonicalisation.
 *
 * These helpers exist for iOS, where `realpath(3)` fails outright on a path that
 * does not exist yet, so `canonicalPath` has to resolve the longest existing
 * prefix and then collapse the rest itself. That code sits in an `actual`, which
 * cannot be executed on this machine — so the string arithmetic was deliberately
 * placed in common code where it can be.
 *
 * It is worth testing precisely because it is security-adjacent: `Workspace`
 * decides whether to hand a path to a tool based on the string this produces. A
 * `..` that survives here is an escape.
 */
class PathResolutionTest {

    @Test
    fun appendsTailSegmentsToACanonicalBase() {
        assertEquals(
            "/private/tmp/root/a/b",
            appendCollapsing("/private/tmp/root", listOf("a", "b")),
        )
    }

    @Test
    fun collapsesDotAndEmptySegments() {
        assertEquals(
            "/private/tmp/root/a/b",
            appendCollapsing("/private/tmp/root", listOf(".", "a", "", "b", ".")),
        )
    }

    @Test
    fun collapsesDotDotInTheTail() {
        // The case that matters: `a/../b` must not be accepted as a way of
        // climbing out, and must also not be left in the string, because the
        // caller compares this text against the canonical root.
        assertEquals(
            "/private/tmp/root/b",
            appendCollapsing("/private/tmp/root", listOf("a", "..", "b")),
        )
    }

    @Test
    fun dotDotStopsAtTheFilesystemRoot() {
        // Three `..` from a three-segment base reach `/` exactly.
        assertEquals("/", appendCollapsing("/private/tmp/root", listOf("..", "..", "..")))
        // Further `..` must not climb above it, and must not consume the root
        // marker either: `/` has to survive as `/`, because an empty string
        // would be a *relative* path, and a relative path is not something the
        // containment check can reason about at all.
        assertEquals("/", appendCollapsing("/private/tmp/root", listOf("..", "..", "..", "..", "..")))
        assertEquals("/", appendCollapsing("/a", listOf("..")))
    }

    @Test
    fun dotDotInTheTailDoesNotConsumeTheBase() {
        // A `..` that follows a tail segment pops that segment, not the base.
        assertEquals("/base/b", appendCollapsing("/base", listOf("a", "..", "b")))
    }

    @Test
    fun collapsingWorksFromTheFilesystemRoot() {
        assertEquals("/x", appendCollapsing("/", listOf("..", "x")))
        assertEquals("/", appendCollapsing("/", listOf("..")))
    }

    @Test
    fun collapsesAPathWithNoBaseToResolveAgainst() {
        assertEquals("/a/b/c", collapseLexically("/a/./b//c"))
        assertEquals("relative/path", collapseLexically("relative/path"))
        assertEquals("/", collapseLexically("/a/../.."))
        // A relative path cannot climb above its own start.
        assertEquals("b", collapseLexically("a/../../b"))
        assertEquals("", collapseLexically(""))
    }
}
