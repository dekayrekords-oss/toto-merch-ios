package com.meowmba.shared.core

import platform.Foundation.NSRecursiveLock

/**
 * iOS actual.
 *
 * `NSRecursiveLock`, not `NSLock`: `@Synchronized` is reentrant, and the ported
 * classes rely on that (a synchronised method that calls another synchronised
 * method on the same instance). `NSLock` is non-reentrant and would deadlock on
 * the first nested acquisition.
 */
actual class PlatformLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
