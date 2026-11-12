@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.meowmba.shared.core

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import kotlin.random.Random

/** iOS actual. */
actual object PlatformTime {
    actual fun currentMillis(): Long =
        (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    actual fun utcOffsetMinutes(): Int =
        (NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate()) / 60L).toInt()
}

actual object PlatformId {
    actual fun shortId(): String {
        val bytes = ByteArray(4)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { HEX[(it.toInt() shr 4) and 0xF].toString() + HEX[it.toInt() and 0xF].toString() }
    }

    /** `NSUUID.UUIDString` is already canonical uppercase; the seam promises lowercase. */
    actual fun uuid(): String = NSUUID().UUIDString.lowercase()

    private val HEX = "0123456789abcdef"
}

actual object DeviceInfo {
    actual fun ramBytes(): Long = platform.Foundation.NSProcessInfo.processInfo.physicalMemory.toLong()
    actual fun cpuCores(): Int = platform.Foundation.NSProcessInfo.processInfo.activeProcessorCount.toInt()
    actual fun osName(): String = "iOS"
    actual fun osVersion(): String = platform.UIKit.UIDevice.currentDevice.systemVersion
    actual fun is64Bit(): Boolean = true
    /** Every iPhone that runs iOS 16.4+ (A12 and newer) has a Neural Engine. */
    actual fun hasNeuralAccelerator(): Boolean = true
    actual fun appVersion(): String =
        platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
}
