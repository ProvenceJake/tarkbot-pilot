package com.xtark.pilot.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetFollowControllerTest {
    @Test
    fun `missing or stale target stops immediately and resets ramp`() {
        val controller = TargetFollowController()
        repeat(5) { controller.follow(centerX = 0.5f, coverage = 0.01f) }

        assertEquals(MotionSetpoint.STOPPED, controller.follow(found = false))
        assertEquals(
            MotionSetpoint.STOPPED,
            controller.follow(ageMs = TargetFollowController.FRAME_TIMEOUT_MS + 1)
        )
    }

    @Test
    fun `left target turns left without translating when far off center`() {
        val result = TargetFollowController().follow(centerX = 0.15f, coverage = 0.01f)

        assertEquals(0.0, result.linearX, 0.001)
        assertTrue(result.angularZ > 0.0)
        assertTrue(result.angularZ <= 0.061)
    }

    @Test
    fun `centered small target ramps forward and color target may reverse`() {
        val forwardController = TargetFollowController()
        val first = forwardController.follow(centerX = 0.5f, coverage = 0.01f)
        val second = forwardController.follow(centerX = 0.5f, coverage = 0.01f)
        val reverse = TargetFollowController().follow(
            centerX = 0.5f,
            coverage = 0.20f,
            allowReverse = true
        )

        assertTrue(first.linearX > 0.0)
        assertTrue(second.linearX > first.linearX)
        assertTrue(reverse.linearX < 0.0)
        assertEquals(0.0, first.angularZ, 0.001)
    }

    @Test
    fun `person target never commands reverse`() {
        val result = TargetFollowController().follow(
            centerX = 0.5f,
            coverage = 0.30f,
            allowReverse = false
        )

        assertEquals(0.0, result.linearX, 0.001)
    }

    @Test
    fun `person keeps approaching until close to requested body width`() {
        val controller = TargetFollowController()
        val approaching = controller.follow(
            centerX = 0.5f,
            coverage = 0.31f,
            allowReverse = false,
            desiredCoverage = 0.34
        )
        val arrived = TargetFollowController().follow(
            centerX = 0.5f,
            coverage = 0.34f,
            allowReverse = false,
            desiredCoverage = 0.34
        )

        assertTrue(approaching.linearX > 0.0)
        assertEquals(0.0, arrived.linearX, 0.001)
    }

    @Test
    fun `final approach reaches an effective wheel command before stopping`() {
        val controller = TargetFollowController()
        var result = MotionSetpoint.STOPPED
        repeat(8) {
            result = controller.follow(
                centerX = 0.5f,
                coverage = 0.31f,
                allowReverse = false,
                desiredCoverage = 0.34
            )
        }

        assertTrue(result.linearX >= 0.044)
        assertTrue(result.linearX <= 0.07)
    }

    @Test
    fun `raw person size applies immediate close range brake despite filter lag`() {
        val controller = TargetFollowController()
        repeat(6) {
            controller.follow(
                centerX = 0.5f,
                coverage = 0.15f,
                allowReverse = false,
                desiredCoverage = 0.34
            )
        }

        val result = controller.follow(
            centerX = 0.5f,
            coverage = 0.36f,
            allowReverse = false,
            desiredCoverage = 0.34
        )

        assertEquals(0.0, result.linearX, 0.001)
    }

    @Test
    fun `far centered person accelerates to the faster follow limit`() {
        val controller = TargetFollowController()
        var result = MotionSetpoint.STOPPED
        repeat(20) {
            result = controller.follow(
                centerX = 0.5f,
                coverage = 0.05f,
                allowReverse = false,
                desiredCoverage = 0.34,
                linearLimitMps = 0.25
            )
        }

        assertEquals(0.25, result.linearX, 0.001)
    }

    @Test
    fun `higher speed still stops immediately for stale or close person`() {
        val staleController = TargetFollowController()
        repeat(20) {
            staleController.follow(
                coverage = 0.05f,
                allowReverse = false,
                desiredCoverage = 0.34,
                linearLimitMps = 0.25
            )
        }
        assertEquals(MotionSetpoint.STOPPED, staleController.follow(found = false))

        val closeController = TargetFollowController()
        repeat(20) {
            closeController.follow(
                coverage = 0.05f,
                allowReverse = false,
                desiredCoverage = 0.34,
                linearLimitMps = 0.25
            )
        }
        assertEquals(
            0.0,
            closeController.follow(
                coverage = 0.36f,
                allowReverse = false,
                desiredCoverage = 0.34,
                linearLimitMps = 0.25
            ).linearX,
            0.001
        )
    }

    @Test
    fun `sudden far off axis target stops translation immediately`() {
        val controller = TargetFollowController()
        repeat(5) { controller.follow(centerX = 0.5f, coverage = 0.01f) }

        val result = controller.follow(centerX = 0.05f, coverage = 0.01f)

        assertEquals(0.0, result.linearX, 0.001)
        assertTrue(result.angularZ > 0.0)
    }

    @Test
    fun `dead zone and hysteresis avoid small motion changes`() {
        val controller = TargetFollowController()
        val centered = controller.follow(centerX = 0.5f, coverage = 0.06f)
        val smallNoise = controller.follow(centerX = 0.54f, coverage = 0.065f)

        assertEquals(MotionSetpoint.STOPPED, centered)
        assertEquals(MotionSetpoint.STOPPED, smallNoise)
    }

    private fun TargetFollowController.follow(
        found: Boolean = true,
        ageMs: Long = 20,
        centerX: Float = 0.5f,
        coverage: Float = 0.02f,
        allowReverse: Boolean = true,
        desiredCoverage: Double = 0.06,
        linearLimitMps: Double = 0.15
    ): MotionSetpoint = evaluate(
        targetFound = found,
        frameAgeMs = ageMs,
        centerX = centerX,
        coverage = coverage,
        desiredCoverage = desiredCoverage,
        userLinearLimitMps = linearLimitMps,
        allowReverse = allowReverse
    )
}
