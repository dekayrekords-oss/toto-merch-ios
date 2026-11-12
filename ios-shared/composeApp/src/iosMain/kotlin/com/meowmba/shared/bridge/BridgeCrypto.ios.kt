@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.meowmba.shared.bridge

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256

/** iOS actual: CommonCrypto (available on iOS without extra linking). */
actual object BridgeCrypto {
    actual fun sha256Hex(bytes: ByteArray): String {
        val out = UByteArray(CC_SHA256_DIGEST_LENGTH)
        // addressOf(0) throws on an empty array; hash one spare byte's address with length 0 instead.
        val data = if (bytes.isEmpty()) ByteArray(1) else bytes
        data.usePinned { input ->
            out.usePinned { output ->
                CC_SHA256(input.addressOf(0), bytes.size.convert(), output.addressOf(0))
            }
        }
        return out.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    actual fun hmacSha256(secret: ByteArray, message: ByteArray): ByteArray {
        val out = UByteArray(32)
        val key = if (secret.isEmpty()) ByteArray(1) else secret
        val body = if (message.isEmpty()) ByteArray(1) else message
        key.usePinned { secretPin ->
            body.usePinned { messagePin ->
                out.usePinned { outPin ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        secretPin.addressOf(0),
                        secret.size.convert(),
                        messagePin.addressOf(0),
                        message.size.convert(),
                        outPin.addressOf(0)
                    )
                }
            }
        }
        return out.toByteArray()
    }
}
