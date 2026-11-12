@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.meowmba.shared.core

import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * iOS actual.
 *
 * `NSTemporaryDirectory()` is inside the app sandbox and is the documented place
 * for scratch data — it is excluded from iCloud backup and the system may purge
 * it, both of which are correct for staged downloads.
 */
actual object PlatformTemp {
    actual fun createDirectory(prefix: String): String {
        val base = NSTemporaryDirectory()
        val unique = NSUUID().UUIDString
        val path = (if (base.endsWith("/")) base else "$base/") + "$prefix-$unique"
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
        return path
    }
}
