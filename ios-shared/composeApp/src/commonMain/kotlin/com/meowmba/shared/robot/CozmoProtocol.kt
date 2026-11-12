package com.meowmba.shared.robot

/** Android CozmoProtocol (build 175/179): Cozmo firmware 2381 wire format, from PyCozmo's MIT reference. */
object CozmoProtocol {
    const val HOST = "172.31.1.1"
    const val PORT = 5551
    const val MOD = 65534
    const val OOB = 65535
    private val magic = byteArrayOf(67, 79, 90, 3, 82, 69, 1)
    class Packet(val type: Int = 4, val id: Int = -1, val data: ByteArray = byteArrayOf())
    data class Frame(val type: Int, val first: Int, val last: Int, val ack: Int, val packets: List<Packet>)
    internal fun buffer(size: Int) = LeWriter(size)
    internal fun reader(bytes: ByteArray) = LeReader(bytes)
    fun next(seq: Int) = (seq + 1) % MOD
    fun distance(from: Int, to: Int) = (to - from + MOD) % MOD

    fun encode(frame: Frame): ByteArray {
        val out = LeWriter(64).put(magic).put(frame.type).putShort(frame.first + 1).putShort(frame.last + 1).putShort(frame.ack + 1)
        frame.packets.forEach { p ->
            if (frame.type != 11) {
                val hasId = p.type == 4 || p.type == 5
                out.put(p.type).putShort(p.data.size + if (hasId) 1 else 0)
                if (hasId) out.put(p.id)
            }
            out.put(p.data)
        }
        return out.array().also { require(it.size <= 1051) { "Cozmo frame too large" } }
    }

    fun decode(bytes: ByteArray): Frame? = runCatching {
        require(bytes.size in 14..1051 && bytes.copyOfRange(0, 7).contentEquals(magic))
        val b = LeReader(bytes, 7)
        val type = b.get() and 255
        require(type in listOf(1, 2, 3, 7, 9, 11))
        fun seq() = ((b.short() and 65535) - 1) and 65535
        val first = seq(); val last = seq(); val ack = seq()
        val packets = mutableListOf<Packet>()
        if (type == 11) {
            require(b.remaining() == 17)
            packets += Packet(11, data = ByteArray(17).also { b.get(it) })
        } else if (type == 7 || type == 9) {
            while (b.hasRemaining()) {
                require(b.remaining() >= 3)
                val pt = b.get() and 255
                val size = b.short() and 65535
                require(pt in listOf(2, 3, 4, 5, 10, 11) && size <= b.remaining())
                val hasId = pt == 4 || pt == 5
                require(!hasId || size > 0)
                val id = if (hasId) b.get() and 255 else -1
                packets += Packet(pt, id, ByteArray(size - if (hasId) 1 else 0).also { b.get(it) })
            }
            val reliable = packets.count { it.type < 5 }
            require(reliable == 0 || (first in 0 until MOD && last == (first + reliable - 1) % MOD))
        } else require(!b.hasRemaining())
        Frame(type, first, last, ack, packets)
    }.getOrNull()

    fun command(id: Int, data: ByteArray = byteArrayOf()) = Packet(id = id, data = data)
    fun floats(vararg values: Float): ByteArray = LeWriter(values.size * 4).apply { values.forEach { require(it.isFinite()); putFloat(it) } }.array()
    fun wheels(left: Float, right: Float) = command(0x32, floats(left.coerceIn(-60f, 60f), right.coerceIn(-60f, 60f), 100f, 100f))
    fun head(degrees: Float) = command(0x37, floats((degrees.coerceIn(-25f, 44.5f) * kotlin.math.PI / 180).toFloat(), 1.5f, 4f, 0f) + byteArrayOf(0))
    fun lift(height: Float) = command(0x36, floats(height.coerceIn(32f, 92f), 1.5f, 4f, 0f) + byteArrayOf(0))
    fun camera(on: Boolean) = command(0x4c, byteArrayOf(if (on) 1 else 0, 4))
    fun light(color: Int, side: Boolean = false): Packet {
        val b = LeWriter((if (side) 2 else 3) * 10 + 1)
        repeat(if (side) 2 else 3) { b.putShort(color).putShort(color).putInt(0).putShort(0) }
        b.put(0)
        return command(if (side) 0x11 else 3, b.array())
    }

    /** Full columns only; no dependence on the decoder's implicit skip/repeat cursor. */
    fun face(pixels: BooleanArray): Packet {
        require(pixels.size == 128 * 32)
        val rle = LeWriter(512)
        for (x in 0 until 128) {
            var y = 0
            while (y < 32) {
                val lit = pixels[y * 128 + x]
                var n = 1
                while (y + n < 32 && pixels[(y + n) * 128 + x] == lit) n++
                rle.put((if (n <= 16) 0x80 else 0xc0) or (((n - 1) % 16) shl 2) or (if (lit) { if (y + n == 32) 2 else 1 } else 0))
                y += n
            }
        }
        val data = rle.array()
        require(data.size <= 1000)
        return command(0x97, LeWriter(2).putShort(data.size).array() + data)
    }

    fun eyes(expression: String, blink: Boolean = false): BooleanArray {
        require(expression in EXPRESSIONS)
        return BooleanArray(4096) { i ->
            val x = i % 128; val y = i / 128
            val cx = if (x < 64) 38 else 90
            val dx = kotlin.math.abs(x - cx)
            when {
                expression == "sleepy" || blink -> dx < 16 && y in 19..21
                expression == "happy" -> dx < 17 && y in (8 + dx / 4)..(12 + dx / 4)
                expression == "surprised" -> dx * dx + (y - 16) * (y - 16) in 70..160
                expression == "curious" -> dx < 14 && y in (if (x < 64) 7..24 else 13..24)
                expression == "sad" -> dx < 16 && y in (15 - dx / 5)..25
                else -> dx < 16 && y in 7..24 && (dx < 12 || y in 10..21)
            }
        }
    }
    val EXPRESSIONS = listOf("neutral", "happy", "curious", "surprised", "sleepy", "sad")
    val COLORS = linkedMapOf("green" to 0x03e0, "blue" to 0x001f, "red" to 0x7c00, "yellow" to 0x7fe0, "purple" to 0x7c1f, "white" to 0x7fff, "off" to 0)
}

data class CozmoTelemetry(
    val batteryVolts: Float, val headDegrees: Float, val liftMm: Float,
    val leftSpeed: Float, val rightSpeed: Float, val x: Float, val y: Float,
    val status: Int, val cliffs: List<Int>, val receivedAt: Long,
) {
    val pickedUp get() = status and 8 != 0
    val falling get() = status and 0x20 != 0
    val onCharger get() = status and 0x1000 != 0
    val charging get() = status and 0x2000 != 0
    val cliff get() = status and 0x4000 != 0
    fun motionBlock(now: Long): String? = when {
        now - receivedAt !in 0..500 -> "Robot sensors are stale"
        pickedUp -> "Cozmo is being held"
        falling -> "Cozmo is falling"
        cliff -> "Cozmo sees an edge"
        onCharger -> "Take Cozmo off his charger first"
        else -> null
    }
    companion object {
        fun decode(data: ByteArray, now: Long): CozmoTelemetry? = runCatching {
            require(data.size >= 91)
            val b = LeReader(data)
            fun f(at: Int) = b.getFloat(at).also { require(it.isFinite()) }
            CozmoTelemetry(f(72), (f(40) * 180 / kotlin.math.PI).toFloat(), f(44), f(32), f(36),
                f(12), f(16), b.getInt(76), (0..3).map { b.getShort(80 + it * 2) and 65535 }, now)
        }.getOrNull()
    }
}

/** Original finite gestures, not Anki animation assets. No autonomous wheel movement. */
data class CozmoReactionStep(val atMs: Long, val head: Float? = null, val lift: Float? = null, val expression: String? = null)

object CozmoReactions {
    val names = listOf("nod", "greet", "celebrate", "peek", "sleep", "wake")
    fun steps(name: String): List<CozmoReactionStep> = when (name) {
        "nod" -> listOf(CozmoReactionStep(0, head = 20f, expression = "happy"),
            CozmoReactionStep(600, head = -15f), CozmoReactionStep(1200, head = 20f), CozmoReactionStep(1800, head = 0f))
        "greet" -> listOf(CozmoReactionStep(0, head = 30f, lift = 75f, expression = "happy"),
            CozmoReactionStep(800, lift = 45f), CozmoReactionStep(1600, lift = 75f), CozmoReactionStep(2400, head = 0f, lift = 32f))
        "celebrate" -> listOf(CozmoReactionStep(0, head = 35f, lift = 92f, expression = "happy"),
            CozmoReactionStep(900, lift = 50f), CozmoReactionStep(1800, lift = 92f), CozmoReactionStep(2700, head = 0f, lift = 32f))
        "peek" -> listOf(CozmoReactionStep(0, head = -20f, expression = "curious"),
            CozmoReactionStep(900, head = 40f, expression = "surprised"), CozmoReactionStep(1800, head = 0f))
        "sleep" -> listOf(CozmoReactionStep(0, head = -25f, lift = 32f, expression = "sleepy"))
        "wake" -> listOf(CozmoReactionStep(0, head = 35f, expression = "surprised"),
            CozmoReactionStep(1000, head = 0f, expression = "neutral"))
        else -> emptyList()
    }
}

/** Bounded reassembly: a missing, repeated, oversized or mixed frame is discarded, never displayed. */
class CozmoCamera {
    private var imageId = -1
    private var nextChunk = 0
    private var chunks = 0
    private var encoding = 0
    private var resolution = 0
    private val data = LeWriter(64_000)
    private var parts = ArrayList<ByteArray>()
    fun reset() { imageId = -1; nextChunk = 0; parts = ArrayList() }
    fun accept(packet: ByteArray): ByteArray? = runCatching {
        require(packet.size >= 20)
        val b = LeReader(packet)
        val id = b.getInt(4)
        val enc = packet[12].toInt() and 255
        val res = packet[13].toInt() and 255
        val count = packet[14].toInt() and 255
        val chunk = packet[15].toInt() and 255
        val length = b.getShort(18) and 65535
        require(count in 1..128 && chunk < count && length == packet.size - 20 && res == 4)
        if (chunk == 0) { reset(); imageId = id; chunks = count; encoding = enc; resolution = res }
        require(id == imageId && chunk == nextChunk && count == chunks && enc == encoding && res == resolution)
        require(parts.sumOf { it.size } + length <= 128_000)
        parts += packet.copyOfRange(20, 20 + length)
        nextChunk++
        if (nextChunk != chunks) return null
        val raw = parts.fold(ByteArray(0)) { acc, p -> acc + p }
        reset()
        when (enc) {
            5 -> raw.takeIf { it.size >= 4 && it[0] == 0xff.toByte() && it[1] == 0xd8.toByte() }
            8 -> miniGray(raw)
            else -> null
        }
    }.getOrElse { reset(); null }

    companion object {
        // Standard Cozmo grayscale JPEG tables, from PyCozmo camera.py (MIT).
        private const val HEADER = "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a181616183123251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffc0000b080128019001011100ffc400d20000010501010101010100000000000000000102030405060708090a0b100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda0008010100003f00"
        internal fun miniGray(raw: ByteArray): ByteArray? {
            if (raw.size < 2 || raw[0].toInt() != 0) return null
            val header = HEADER.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            header[94] = 0; header[95] = 240.toByte(); header[96] = 1; header[97] = 64
            var end = raw.size
            while (end > 1 && raw[end - 1] == 0xff.toByte()) end--
            if (end <= 1) return null
            val out = LeWriter(raw.size + header.size + 64).put(header)
            for (i in 1 until end) { out.put(raw[i].toInt() and 255); if (raw[i] == 0xff.toByte()) out.put(0) }
            out.put(255); out.put(217)
            return out.array()
        }
    }
}
