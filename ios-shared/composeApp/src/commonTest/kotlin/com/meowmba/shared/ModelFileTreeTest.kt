package com.meowmba.shared

import com.meowmba.shared.agent.ModelFile
import com.meowmba.shared.agent.relativeTo
import com.meowmba.shared.agent.walkTopDown
import com.meowmba.shared.core.PlatformTemp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The common replacement for `java.io.File.walkTopDown` / `relativeTo`.
 *
 * Both the sandbox's `list` and the `files.list` tool walk a tree, so the walk
 * is shared. The bounds are load-bearing: the tool version relies on `maxDepth`
 * and `onEnter` to keep a model from enumerating a phone's whole filesystem, so
 * both are pinned here rather than only exercised through the tool.
 */
class ModelFileTreeTest {

    /** root/ , root/a/ , root/a/b/ , root/a/b/deep.txt , root/a/one.txt , root/two.txt */
    private fun tree(): ModelFile {
        val root = ModelFile(PlatformTemp.createDirectory("tree"))
        ModelFile("${root.path}/a/b/deep.txt").writeBytesAtomic("d".encodeToByteArray())
        ModelFile("${root.path}/a/one.txt").writeBytesAtomic("o".encodeToByteArray())
        ModelFile("${root.path}/two.txt").writeBytesAtomic("t".encodeToByteArray())
        return root
    }

    @Test
    fun maxDepthZeroYieldsOnlyTheReceiver() {
        val root = tree()
        assertEquals(listOf(root.path), root.walkTopDown(maxDepth = 0).map { it.path }.toList())
    }

    @Test
    fun maxDepthOneYieldsTheReceiverAndItsDirectChildren() {
        val root = tree()
        assertEquals(
            setOf(root.path, "${root.path}/a", "${root.path}/two.txt"),
            root.walkTopDown(maxDepth = 1).map { it.path }.toSet(),
        )
    }

    @Test
    fun walkYieldsParentsBeforeTheirChildren() {
        val root = tree()
        val walked = root.walkTopDown().map { it.path }.toList()
        val a = "${root.path}/a"
        val deep = "$a/b/deep.txt"
        // The receiver is first, and nothing is yielded before the directory it
        // lives in -- a consumer that builds a tree in one pass depends on this.
        assertEquals(root.path, walked.first())
        assertTrue(walked.indexOf(a) < walked.indexOf(deep), "a must precede a/b/deep.txt")
        assertEquals(6, walked.size)
    }

    @Test
    fun onEnterFalseSkipsTheWholeSubtree() {
        val root = tree()
        val walked = root.walkTopDown(onEnter = { it.name != "a" }).map { it.path }.toList()
        // The refused directory is still yielded -- onEnter decides whether to
        // *descend*, not whether to report the directory itself, which matches
        // FileTreeWalk.onEnter. Nothing beneath it may appear.
        assertTrue(walked.contains("${root.path}/a"), "a/ itself is still reported")
        assertFalse(
            walked.any { it.startsWith("${root.path}/a/") },
            "nothing under a/ may be walked",
        )
        assertTrue(walked.contains("${root.path}/two.txt"), "siblings of a/ are still walked")
    }

    @Test
    fun relativeToStripsTheBase() {
        val root = tree()
        assertEquals("a/one.txt", ModelFile("${root.path}/a/one.txt").relativeTo(root))
        assertEquals("", root.relativeTo(root))
    }

    @Test
    fun relativeToRefusesAPathOutsideTheBase() {
        val root = tree()
        // A sibling whose name merely shares a prefix must not silently yield a
        // bogus relative path -- that is how a tool would hand the model a path
        // outside its workspace.
        assertFailsWith<IllegalArgumentException> {
            ModelFile("${root.path}-extra/secret.txt").relativeTo(root)
        }
    }
}
