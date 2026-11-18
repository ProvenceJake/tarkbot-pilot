package com.xtark.pilot.control

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSafetyGateTest {
    @Test
    fun staleTouchHeartbeatAlwaysProducesStop() {
        val result = activeSetpoint(heartbeatAgeMs = MotionSafetyGate.INPUT_TIMEOUT_MS + 1)

        assertEquals(MotionSetpoint.STOPPED, result)
    }

    @Test
    fun disarmedOrDisconnectedAlwaysProducesStop() {
        assertEquals(MotionSetpoint.STOPPED, activeSetpoint(armed = false))
        assertEquals(MotionSetpoint.STOPPED, activeSetpoint(connected = false))
    }

    @Test
    fun freshHeldInputProducesLimitedMotion() {
        val result = activeSetpoint(heartbeatAgeMs = 50, maxLinearMps = 9.0)

        assertEquals(0.40, result.linearX, 0.001)
        assertEquals(-0.20, result.linearY, 0.001)
        assertEquals(0.50, result.angularZ, 0.001)
    }

    private fun activeSetpoint(
        armed: Boolean = true,
        connected: Boolean = true,
        heartbeatAgeMs: Long = 0,
        maxLinearMps: Double = 0.20
    ): MotionSetpoint = MotionSafetyGate.evaluate(
        armed = armed,
        connected = connected,
        inputActive = true,
        heartbeatAgeMs = heartbeatAgeMs,
        normalizedX = 1f,
        normalizedY = -0.5f,
        turnDirection = 1,
        maxLinearMps = maxLinearMps,
        angularRadS = 0.50
    )
}
