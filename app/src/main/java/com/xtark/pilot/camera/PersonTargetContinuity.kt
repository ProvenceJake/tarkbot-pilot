package com.xtark.pilot.camera

import kotlin.math.abs
import kotlin.math.max

object PersonTargetContinuity {
    fun isPlausible(previous: PersonCandidate?, current: PersonCandidate): Boolean {
        if (previous == null) return true
        if (previous.trackingId != current.trackingId) return false

        val previousWidth = previous.bodyWidthCoverage.coerceAtLeast(0.001f)
        val currentWidth = current.bodyWidthCoverage.coerceAtLeast(0.001f)
        val widthRatio = currentWidth / previousWidth
        if (widthRatio !in MIN_WIDTH_RATIO..MAX_WIDTH_RATIO) return false

        val previousHeight = (previous.bottom - previous.top).coerceAtLeast(0.001f)
        val maxHorizontalJump = max(MIN_HORIZONTAL_JUMP, previousWidth * 1.8f)
        val maxVerticalJump = max(MIN_VERTICAL_JUMP, previousHeight * 1.6f)
        return abs(current.centerX - previous.centerX) <= maxHorizontalJump &&
            abs(current.centerY - previous.centerY) <= maxVerticalJump
    }

    private const val MIN_WIDTH_RATIO = 0.48f
    private const val MAX_WIDTH_RATIO = 2.10f
    private const val MIN_HORIZONTAL_JUMP = 0.18f
    private const val MIN_VERTICAL_JUMP = 0.20f
}
