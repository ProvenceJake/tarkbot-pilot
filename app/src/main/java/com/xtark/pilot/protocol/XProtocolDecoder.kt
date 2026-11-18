package com.xtark.pilot.protocol

data class XProtocolFrame(
    val code: Int,
    val bytes: ByteArray
) {
    val length: Int get() = bytes.size
    val isStatusFrame: Boolean
        get() = code == STATUS_FRAME_CODE && bytes.size >= MIN_STATUS_FRAME_LENGTH
    val linearX: Double? get() = velocityAt(16)
    val linearY: Double? get() = velocityAt(18)
    val angularZ: Double? get() = velocityAt(20)
    val batteryVoltage: Double?
        get() {
            if (!isStatusFrame) return null
            val raw = ((bytes[22].toInt() and 0xff) shl 8) or (bytes[23].toInt() and 0xff)
            return if (raw in 500..3000) raw / 100.0 else null
        }

    private fun velocityAt(offset: Int): Double? {
        if (!isStatusFrame) return null
        val raw = ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
        return raw.toShort() / 1000.0
    }

    fun preview(maxBytes: Int = 18): String = bytes
        .take(maxBytes)
        .joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    companion object {
        const val STATUS_FRAME_CODE = 0x10
        const val MIN_STATUS_FRAME_LENGTH = 25
    }
}

class XProtocolDecoder(
    private val onFrame: (XProtocolFrame) -> Unit
) {
    private val buffer = ArrayList<Byte>()
    var validFrameCount: Long = 0
        private set
    var checksumErrorCount: Long = 0
        private set

    @Synchronized
    fun feed(data: ByteArray) {
        data.forEach(buffer::add)
        while (buffer.size >= 3) {
            if ((buffer[0].toInt() and 0xff) != 0xAA ||
                (buffer[1].toInt() and 0xff) != 0x55
            ) {
                buffer.removeAt(0)
                continue
            }

            val frameLength = buffer[2].toInt() and 0xff
            if (frameLength !in 5..128) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < frameLength) return

            val frame = ByteArray(frameLength) { buffer[it] }
            val calculated = frame.dropLast(1).sumOf { it.toInt() and 0xff } and 0xff
            val received = frame.last().toInt() and 0xff
            if (calculated != received) {
                checksumErrorCount++
                buffer.removeAt(0)
                continue
            }

            repeat(frameLength) { buffer.removeAt(0) }
            validFrameCount++
            onFrame(XProtocolFrame(code = frame[3].toInt() and 0xff, bytes = frame))
        }

        if (buffer.size > 2048) {
            buffer.subList(0, buffer.size - 256).clear()
        }
    }

    @Synchronized
    fun reset() {
        buffer.clear()
        validFrameCount = 0
        checksumErrorCount = 0
    }
}
