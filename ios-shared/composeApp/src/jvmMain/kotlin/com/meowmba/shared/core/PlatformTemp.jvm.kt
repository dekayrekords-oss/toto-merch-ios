package com.meowmba.shared.core

import java.nio.file.Files

/** JVM actual. */
actual object PlatformTemp {
    actual fun createDirectory(prefix: String): String =
        Files.createTempDirectory(prefix).toString()
}
