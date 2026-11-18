package com.xtark.pilot.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyAppearanceExtractorTest {
    @Test
    fun `appearance region stays valid for clipped detector box`() {
        val region = BodyAppearanceExtractor.appearanceRegion(
            bitmapWidth = 640,
            bitmapHeight = 480,
            left = -0.08f,
            top = -0.04f,
            right = 1.12f,
            bottom = 1.07f
        )

        assertTrue(region.left in 0 until 640)
        assertTrue(region.right in 1..640)
        assertTrue(region.top in 0 until 480)
        assertTrue(region.bottom in 1..480)
        assertTrue(region.right > region.left)
        assertTrue(region.bottom > region.top)
    }

    @Test
    fun `appearance region handles reversed coordinates without empty crop`() {
        val region = BodyAppearanceExtractor.appearanceRegion(
            bitmapWidth = 640,
            bitmapHeight = 480,
            left = 0.80f,
            top = 0.90f,
            right = 0.20f,
            bottom = 0.10f
        )

        assertTrue(region.right > region.left)
        assertTrue(region.bottom > region.top)
    }

    @Test
    fun `color bins remain within appearance vector`() {
        val samples = listOf(
            Triple(255, 0, 0),
            Triple(0, 255, 0),
            Triple(0, 0, 255),
            Triple(255, 255, 255),
            Triple(0, 0, 0)
        )

        samples.forEach { (red, green, blue) ->
            assertTrue(
                BodyAppearanceExtractor.colorBin(red, green, blue) in
                    0 until BodyAppearanceExtractor.COLOR_BINS_PER_PART
            )
        }
        assertEquals(0, BodyAppearanceExtractor.colorBin(255, 0, 0))
    }
}
