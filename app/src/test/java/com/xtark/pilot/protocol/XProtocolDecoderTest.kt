package com.xtark.pilot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XProtocolDecoderTest {
    @Test
    fun decodesFrameArrivingInTwoUsbReads() {
        val frames = mutableListOf<XProtocolFrame>()
        val decoder = XProtocolDecoder(frames::add)
        val frame = frameOf(0x10, 0x01)

        decoder.feed(frame.copyOfRange(0, 2))
        decoder.feed(frame.copyOfRange(2, frame.size))

        assertEquals(1, decoder.validFrameCount)
        assertEquals(0, decoder.checksumErrorCount)
        assertEquals(0x10, frames.single().code)
        assertFalse(frames.single().isStatusFrame)
    }

    @Test
    fun rejectsBadChecksumAndResynchronizes() {
        val frames = mutableListOf<XProtocolFrame>()
        val decoder = XProtocolDecoder(frames::add)
        val invalid = frameOf(0x10, 0x01).also { it[it.lastIndex] = 0 }
        val valid = frameOf(0x11, 0x02)

        decoder.feed(byteArrayOf(0x00, 0x7F) + invalid + valid)

        assertEquals(1, decoder.validFrameCount)
        assertEquals(1, decoder.checksumErrorCount)
        assertEquals(0x11, frames.single().code)
    }

    @Test
    fun exposesObservedBatteryFieldWithoutGuessingOtherFrames() {
        val frame = ByteArray(25)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55
        frame[2] = 25
        frame[3] = 0x10
        frame[16] = 0x00
        frame[17] = 0x96.toByte()
        frame[18] = 0xFF.toByte()
        frame[19] = 0x9C.toByte()
        frame[20] = 0x01
        frame[21] = 0xF4.toByte()
        frame[22] = 0x04
        frame[23] = 0xB0.toByte()
        frame[24] = checksum(frame)

        val decoded = XProtocolFrame(0x10, frame)

        assertTrue(decoded.isStatusFrame)
        assertEquals(12.0, decoded.batteryVoltage!!, 0.001)
        assertEquals(0.15, decoded.linearX!!, 0.001)
        assertEquals(-0.10, decoded.linearY!!, 0.001)
        assertEquals(0.50, decoded.angularZ!!, 0.001)
        assertTrue(decoded.preview().startsWith("AA 55 19 10"))
    }

    @Test
    fun commandEchoNeverCountsAsStatusTelemetry() {
        val echoedCommand = XProtocolFrame(
            XProtocolEncoder.VELOCITY_FRAME_CODE,
            XProtocolEncoder.velocityFrame(0.1, 0.0, 0.0)
        )

        assertFalse(echoedCommand.isStatusFrame)
        assertNull(echoedCommand.linearX)
        assertNull(echoedCommand.batteryVoltage)
    }

    private fun frameOf(code: Int, payload: Int): ByteArray {
        val frame = byteArrayOf(
            0xAA.toByte(),
            0x55,
            0x06,
            code.toByte(),
            payload.toByte(),
            0x00
        )
        frame[frame.lastIndex] = checksum(frame)
        return frame
    }

    private fun checksum(frame: ByteArray): Byte =
        (frame.dropLast(1).sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
}
