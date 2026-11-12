package com.meowmba.shared.robot

/** Little-endian writer/reader standing in for java.nio.ByteBuffer in the Cozmo wire format. */
internal class LeWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    var size = 0; private set
    private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
    fun put(b: Int) = apply { ensure(1); buf[size++] = b.toByte() }
    fun put(bytes: ByteArray) = apply { ensure(bytes.size); bytes.copyInto(buf, size); size += bytes.size }
    fun putShort(v: Int) = apply { put(v and 255); put((v shr 8) and 255) }
    fun putInt(v: Int) = apply { for (i in 0..3) put((v shr (8 * i)) and 255) }
    fun putLong(v: Long) = apply { for (i in 0..7) put(((v shr (8 * i)) and 255).toInt()) }
    fun putFloat(v: Float) = putInt(v.toRawBits())
    fun putDouble(v: Double) = putLong(v.toRawBits())
    fun array(): ByteArray = buf.copyOf(size)
}

internal class LeReader(private val bytes: ByteArray, var position: Int = 0) {
    fun remaining() = bytes.size - position
    fun hasRemaining() = position < bytes.size
    fun get(): Int = bytes[position++].toInt()
    fun get(dst: ByteArray) { bytes.copyInto(dst, 0, position, position + dst.size); position += dst.size }
    fun short(): Int { val v = getShort(position); position += 2; return v }
    fun int(): Int { val v = getInt(position); position += 4; return v }
    fun getShort(at: Int): Int = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    fun getInt(at: Int): Int = (0..3).fold(0) { acc, i -> acc or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
    fun getFloat(at: Int): Float = Float.fromBits(getInt(at))
}
