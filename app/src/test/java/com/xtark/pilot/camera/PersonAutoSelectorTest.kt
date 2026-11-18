package com.xtark.pilot.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonAutoSelectorTest {
    @Test
    fun `locks centered visible person after consecutive observations`() {
        val selector = PersonAutoSelector(requiredObservations = 2)
        val centered = candidate(7, 0.38f, 0.16f, 0.62f, 0.88f, 0.82f)
        val edge = candidate(9, 0.02f, 0.18f, 0.24f, 0.84f, 0.95f)

        assertNull(selector.observe(listOf(edge, centered)))
        assertEquals(7, selector.observe(listOf(centered, edge))?.trackingId)
    }

    @Test
    fun `does not confirm an alternating transient candidate`() {
        val selector = PersonAutoSelector(requiredObservations = 2)
        val left = candidate(2, 0.24f, 0.20f, 0.44f, 0.82f, 0.80f)
        val right = candidate(3, 0.56f, 0.20f, 0.76f, 0.82f, 0.80f)

        assertNull(selector.observe(listOf(left)))
        assertNull(selector.observe(listOf(right)))
        assertEquals(3, selector.observe(listOf(right))?.trackingId)
    }

    @Test
    fun `empty frame resets pending confirmation`() {
        val selector = PersonAutoSelector(requiredObservations = 2)
        val person = candidate(4, 0.36f, 0.18f, 0.64f, 0.90f, 0.90f)

        assertNull(selector.observe(listOf(person)))
        assertNull(selector.observe(emptyList()))
        assertNull(selector.observe(listOf(person)))
        assertEquals(4, selector.observe(listOf(person))?.trackingId)
    }

    private fun candidate(
        id: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float
    ) = PersonCandidate(id, left, top, right, bottom, 640, 480, confidence)
}
