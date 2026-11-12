package com.meowmba.shared.agent

/**
 * Tree traversal for [ModelFile].
 *
 * `java.io.File.walkTopDown` and `relativeTo` are JVM-only extensions, and two
 * callers need the same walk with the same bounds — the sandbox's `list` and the
 * `files.list` tool — so the walk lives here once rather than being re-derived
 * per call site.
 *
 * Written as an explicit stack rather than a recursive `Sequence` so that a
 * deep tree cannot exhaust the call stack on a phone.
 */

/**
 * Every path at or below this one, parents before children.
 *
 * [maxDepth] counts levels below the receiver, which is depth 0 — matching
 * `FileTreeWalk.maxDepth`, so `maxDepth(0)` yields only the receiver and
 * `maxDepth(1)` yields the receiver plus its direct children.
 *
 * [onEnter] is consulted for each **directory** (including the receiver) before
 * its contents are read; returning false skips that subtree. This is the second
 * line of defence for the sandbox: even if path resolution let something
 * through, a walk that refuses to descend outside the root cannot enumerate
 * outside it.
 *
 * A child that cannot be listed is skipped rather than throwing — an
 * unreadable subdirectory should not abort a listing of its siblings.
 */
fun ModelFile.walkTopDown(
    maxDepth: Int = Int.MAX_VALUE,
    onEnter: (ModelFile) -> Boolean = { true },
): Sequence<ModelFile> = sequence {
    val stack = ArrayDeque<Pair<ModelFile, Int>>()
    stack.addLast(this@walkTopDown to 0)
    while (stack.isNotEmpty()) {
        val (file, depth) = stack.removeLast()
        yield(file)
        if (depth >= maxDepth || !file.isDirectory()) continue
        if (!onEnter(file)) continue
        val children = file.listFiles()
        // Reversed so siblings are yielded in listing order despite the stack.
        for (i in children.indices.reversed()) {
            stack.addLast(children[i] to depth + 1)
        }
    }
}

/**
 * This path expressed relative to [base], always with `/` separators.
 *
 * Requires that this path really is under [base]: a silent wrong answer here
 * would surface as a tool handing the model a path outside its workspace, so
 * this fails loudly instead, like `java.io.File.relativeTo`.
 *
 * Returns `""` when the two paths are equal.
 */
fun ModelFile.relativeTo(base: ModelFile): String {
    val root = base.path.trimEnd('/')
    val self = path.trimEnd('/')
    if (self == root) return ""
    require(self.startsWith("$root/")) {
        "$self is not inside $root"
    }
    return self.removePrefix("$root/")
}
