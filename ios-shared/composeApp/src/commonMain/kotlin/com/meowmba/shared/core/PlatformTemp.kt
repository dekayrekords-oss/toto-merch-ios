package com.meowmba.shared.core

/**
 * Scratch directories, in common code.
 *
 * `java.nio.file.Files.createTempDirectory` is JVM-only. The model-download
 * layer needs somewhere to stage partial downloads before renaming them into
 * place, and the tests need somewhere to run without touching the user's real
 * model directory — so the seam lives here.
 *
 * Returns a path rather than a file handle deliberately: the only file
 * abstraction in this module is `ModelFile`, which lives in the `agent`
 * package, and core should not depend on it.
 */
expect object PlatformTemp {
    /**
     * Absolute path to a fresh, uniquely-named, empty directory.
     *
     * The directory is created before this returns, so a caller can write into
     * it immediately. [prefix] is a human-readable hint only; uniqueness is the
     * implementation's responsibility.
     */
    fun createDirectory(prefix: String): String
}
