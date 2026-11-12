package com.meowmba.shared.bridge

/**
 * Platform crypto primitives used by the desktop bridge protocol.
 * Actuals: JVM/Android -> java.security + javax.crypto;
 *         iOS -> CommonCrypto (CC_SHA256 / CCHmac).
 */
expect object BridgeCrypto {
    fun sha256Hex(bytes: ByteArray): String
    fun hmacSha256(secret: ByteArray, message: ByteArray): ByteArray
}
