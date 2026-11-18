package com.xtark.pilot.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonTargetContinuityTest {
    @Test
    fun `accepts first observation and smooth movement of same id`() {
        val first = candidate(id = 4, left = 0.30f, top = 0.20f, right = 0.45f, bottom = 0.42f)
        val moved = candidate(id = 4, left = 0.36f, top = 0.23f, right = 0.52f, bottom = 0.46f)

        assertTrue(PersonTargetContinuity.isPlausible(null, first))
        assertTrue(PersonTargetContinuity.isPlausible(first, moved))
    }

    @Test
    fun `rejects id change sudden teleport and implausible scale jump`() {
        val previous = candidate(id = 4, left = 0.10f, top = 0.20f, right = 0.24f, bottom = 0.42f)

        assertFalse(PersonTargetContinuity.isPlausible(previous, previous.copy(trackingId = 9)))
        assertFalse(
            PersonTargetContinuity.isPlausible(
                previous,
                candidate(id = 4, left = 0.65f, top = 0.20f, right = 0.79f, bottom = 0.42f)
            )
        )
        assertFalse(
            PersonTargetContinuity.isPlausible(
                previous,
                candidate(id = 4, left = 0.05f, top = 0.05f, right = 0.55f, bottom = 0.75f)
            )
        )
    }

    private fun candidate(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ) = PersonCandidate(id, left, top, right, bottom, 640, 480)
}
