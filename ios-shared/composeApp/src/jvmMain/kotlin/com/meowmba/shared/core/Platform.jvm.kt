package com.meowmba.shared.core

/** JVM/Android actual. */
actual object PlatformTime {
    actual fun currentMillis(): Long = System.currentTimeMillis()

    actual fun utcOffsetMinutes(): Int =
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
}

actual object PlatformId {
    actual fun shortId(): String {
        val bytes = ByteArray(4)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    actual fun uuid(): String = java.util.UUID.randomUUID().toString()
}

actual object DeviceInfo {
    actual fun ramBytes(): Long = Runtime.getRuntime().maxMemory()
    actual fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()
    actual fun osName(): String = System.getProperty("os.name") ?: "JVM"
    actual fun osVersion(): String = System.getProperty("os.version") ?: ""
    actual fun is64Bit(): Boolean = (System.getProperty("os.arch") ?: "").contains("64")
    actual fun hasNeuralAccelerator(): Boolean = false
    actual fun appVersion(): String = "dev"
}
