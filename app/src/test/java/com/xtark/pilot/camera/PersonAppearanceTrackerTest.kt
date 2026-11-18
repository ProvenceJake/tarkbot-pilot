package com.xtark.pilot.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonAppearanceTrackerTest {
    @Test
    fun `selected identity survives two people crossing`() {
        val tracker = PersonAppearanceTracker()
        val initial = tracker.update(
            listOf(body(0.15f, 0.35f, appearanceBin = 2), body(0.65f, 0.85f, appearanceBin = 9)),
            nowMs = 1_000L,
            selectedId = null
        )
        val selectedId = initial.first { it.observation.left < 0.2f }.trackingId

        val crossed = tracker.update(
            listOf(body(0.40f, 0.60f, appearanceBin = 2), body(0.45f, 0.65f, appearanceBin = 9)),
            nowMs = 1_120L,
            selectedId = selectedId
        )

        val selected = crossed.first { it.trackingId == selectedId }
        assertEquals(2, selected.observation.appearance.bins.indexOfFirst { it > 0f })
    }

    @Test
    fun `selected track rejects a different looking person in the same place`() {
        val tracker = PersonAppearanceTracker()
        val selectedId = tracker.update(
            listOf(body(0.25f, 0.50f, appearanceBin = 1)),
            nowMs = 1_000L,
            selectedId = null
        ).single().trackingId

        val result = tracker.update(
            listOf(body(0.25f, 0.50f, appearanceBin = 12)),
            nowMs = 1_100L,
            selectedId = selectedId
        )

        assertFalse(result.any { it.trackingId == selectedId })
        assertNotEquals(selectedId, result.single().trackingId)
    }

    @Test
    fun `selected track can reacquire same appearance after brief occlusion`() {
        val tracker = PersonAppearanceTracker()
        val selectedId = tracker.update(
            listOf(body(0.05f, 0.27f, appearanceBin = 4)),
            nowMs = 1_000L,
            selectedId = null
        ).single().trackingId
        assertTrue(tracker.update(emptyList(), 1_250L, selectedId).isEmpty())

        val reacquired = tracker.update(
            listOf(body(0.23f, 0.45f, appearanceBin = 4)),
            nowMs = 1_550L,
            selectedId = selectedId
        )

        assertEquals(selectedId, reacquired.single().trackingId)
    }

    @Test
    fun `expired track is not silently resurrected`() {
        val tracker = PersonAppearanceTracker()
        val oldId = tracker.update(
            listOf(body(0.20f, 0.42f, appearanceBin = 3)),
            nowMs = 1_000L,
            selectedId = null
        ).single().trackingId

        val result = tracker.update(
            listOf(body(0.20f, 0.42f, appearanceBin = 3)),
            nowMs = 3_600L,
            selectedId = oldId
        )

        assertNotEquals(oldId, result.single().trackingId)
    }

    private fun body(left: Float, right: Float, appearanceBin: Int): BodyObservation {
        val bins = FloatArray(16)
        bins[appearanceBin] = 1f
        return BodyObservation(
            left = left,
            top = 0.12f,
            right = right,
            bottom = 0.88f,
            confidence = 0.85f,
            appearance = AppearanceVector(bins)
        )
    }
}
