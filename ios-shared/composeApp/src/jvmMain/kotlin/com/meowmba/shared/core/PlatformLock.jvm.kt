package com.meowmba.shared.core

import java.util.concurrent.locks.ReentrantLock

/** JVM actual. `ReentrantLock` matches `@Synchronized`'s reentrancy. */
actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
