package com.meowmba.shared.core

import kotlinx.coroutines.sync.Mutex

/**
 * Tiny non-suspending critical section for cross-thread flags and native
 * callbacks (JVM `synchronized` is not available in common code). The guarded
 * sections are micro-short (list add, boolean set); the spin is negligible.
 */
class SpinGuard {
    private val mutex = Mutex()

    fun <T> guard(block: () -> T): T {
        while (!mutex.tryLock()) { /* wait for the holder */ }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
