package com.xtark.pilot.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class XProtocolEncoderTest {
    @Test
    fun stopFrameMatchesVendorXProtocolLayout() {
        assertArrayEquals(
            byteArrayOf(
                0xAA.toByte(), 0x55, 0x0B, 0x50,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x5A
            ),
            XProtocolEncoder.STOP_FRAME
        )
    }

    @Test
    fun velocityUsesSignedBigEndianAndScaleOfOneThousand() {
        val frame = XProtocolEncoder.velocityFrame(0.15, -0.10, 0.50)

        assertEquals(0x00, frame[4].unsigned())
        assertEquals(0x96, frame[5].unsigned())
        assertEquals(0xFF, frame[6].unsigned())
        assertEquals(0x9C, frame[7].unsigned())
        assertEquals(0x01, frame[8].unsigned())
        assertEquals(0xF4, frame[9].unsigned())
        assertValidChecksum(frame)
    }

    @Test
    fun velocityIsClampedToAppSafetyLimits() {
        val frame = XProtocolEncoder.velocityFrame(9.0, -9.0, 9.0)

        assertEquals(400, signedInt16(frame[4], frame[5]))
        assertEquals(-400, signedInt16(frame[6], frame[7]))
        assertEquals(1000, signedInt16(frame[8], frame[9]))
    }

    @Test
    fun r20MecTypeFrameUsesModelOne() {
        val frame = XProtocolEncoder.r20MecTypeFrame()

        assertEquals(0x5A, frame[3].unsigned())
        assertEquals(0x01, frame[4].unsigned())
        assertValidChecksum(frame)
    }

    private fun signedInt16(high: Byte, low: Byte): Int =
        (((high.unsigned() shl 8) or low.unsigned()).toShort()).toInt()

    private fun assertValidChecksum(frame: ByteArray) {
        val expected = frame.dropLast(1).sumOf { it.unsigned() } and 0xFF
        assertEquals(expected, frame.last().unsigned())
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF
}
