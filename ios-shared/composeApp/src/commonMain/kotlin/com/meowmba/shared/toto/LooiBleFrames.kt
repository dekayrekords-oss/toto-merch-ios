package com.meowmba.shared.toto

/**
 * Conservatively capped LOOI BLE frames for the merch app.
 * 17-byte fe00 scripts match the bb6e8ce template that moved the base.
 * rotateFrame with speed 0 is still a rotation script, not a stop — never
 * send it as Brake. Stop is all-zero ff02/fed0 plus a neutral fed1 neck.
 */
object LooiBleFrames {
    const val FRAME_SIZE = 17
    const val MAX_SPEED_PERCENT = 30
    const val MAX_TICKS = 4
    const val NECK_CENTER = 0x80
    const val NECK_MIN = 0x60
    const val NECK_MAX = 0xA0
    const val WATCHDOG_MS = 1500L
    const val NOD_DOWN = 0x70
    const val NOD_UP = 0x90

    private object Field {
        const val SEQ = 0
        const val OPCODE = 1
        const val SUB_OP = 2
        const val MASK_A = 3
        const val MASK_B = 4
        const val PAYLOAD = 5 // bytes 5..8
        const val VALUE = 9
        const val PARAM_1 = 10
        const val PARAM_2 = 11
        const val DURATION = 12
        const val PARAM_3 = 13
        const val PARAM_4 = 14
        const val RES = 15
        const val END = 16
    }

    private var seq = 0

    private fun nextSeq(): Int {
        val current = seq and 0xFF
        seq = (seq + 1) and 0xFF
        return current
    }

    fun rotate(speedPercent: Int, ticks: Int, right: Boolean = false): ByteArray {
        val mag = speedPercent.coerceIn(0, MAX_SPEED_PERCENT)
        val value = if (right && mag > 0) (256 - mag) and 0xFF else mag
        val duration = ticks.coerceIn(1, MAX_TICKS)
        val out = ByteArray(FRAME_SIZE)
        out[Field.SEQ] = nextSeq().toByte()
        out[Field.OPCODE] = 0x07
        out[Field.SUB_OP] = 0x00
        out[Field.MASK_A] = 0xFF.toByte()
        out[Field.MASK_B] = 0x05
        out[Field.VALUE] = value.toByte()
        out[Field.PARAM_1] = 0x02
        out[Field.PARAM_2] = 0x0A
        out[Field.DURATION] = duration.toByte()
        out[Field.PARAM_3] = 0x02
        out[Field.PARAM_4] = 0x14
        out[Field.END] = 0x02
        return out
    }

    /** Map pitch -1 (up) .. 1 (down) into a conservative fed1 byte. 0 is center. */
    fun neckByte(pitch: Float): Int {
        val p = if (pitch.isNaN()) 0f else pitch.coerceIn(-1f, 1f)
        val raw = (NECK_CENTER + p * (NECK_MAX - NECK_CENTER)).toInt()
        return raw.coerceIn(NECK_MIN, NECK_MAX)
    }

    fun motorZero(bytes: Int = 4): ByteArray = ByteArray(bytes)
}
