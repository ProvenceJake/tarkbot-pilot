package com.xtark.pilot.protocol

import kotlin.math.roundToInt

object XProtocolEncoder {
    const val VELOCITY_FRAME_CODE = 0x50
    const val ROBOT_TYPE_FRAME_CODE = 0x5A
    const val R20_MEC_TYPE = 0x01

    const val MAX_LINEAR_MPS = 0.40
    const val MAX_ANGULAR_RAD_S = 1.00

    val STOP_FRAME: ByteArray
        get() = velocityFrame(0.0, 0.0, 0.0)

    /**
     * OpenCTR speed payload: X, Y and W as signed int16, big-endian, scaled by 1000.
     * X/Y are m/s and W is rad/s, matching the vendor tarkbot_robot ROS driver.
     */
    fun velocityFrame(
        linearX: Double,
        linearY: Double,
        angularZ: Double
    ): ByteArray {
        val x = scaledInt16(linearX.coerceIn(-MAX_LINEAR_MPS, MAX_LINEAR_MPS))
        val y = scaledInt16(linearY.coerceIn(-MAX_LINEAR_MPS, MAX_LINEAR_MPS))
        val w = scaledInt16(angularZ.coerceIn(-MAX_ANGULAR_RAD_S, MAX_ANGULAR_RAD_S))
        return frame(
            VELOCITY_FRAME_CODE,
            byteArrayOf(
                highByte(x), lowByte(x),
                highByte(y), lowByte(y),
                highByte(w), lowByte(w)
            )
        )
    }

    fun r20MecTypeFrame(): ByteArray =
        frame(ROBOT_TYPE_FRAME_CODE, byteArrayOf(R20_MEC_TYPE.toByte()))

    fun frame(code: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 123) { "X-Protocol payload is too long" }
        val result = ByteArray(payload.size + 5)
        result[0] = 0xAA.toByte()
        result[1] = 0x55
        result[2] = result.size.toByte()
        result[3] = code.toByte()
        payload.copyInto(result, destinationOffset = 4)
        result[result.lastIndex] = checksum(result)
        return result
    }

    private fun scaledInt16(value: Double): Int =
        (value * 1000.0).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

    private fun highByte(value: Int): Byte = (value shr 8).toByte()

    private fun lowByte(value: Int): Byte = value.toByte()

    private fun checksum(frame: ByteArray): Byte =
        (frame.dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
}
