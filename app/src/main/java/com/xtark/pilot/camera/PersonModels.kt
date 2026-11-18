package com.xtark.pilot.camera

import kotlin.math.max

data class PersonCandidate(
    val trackingId: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val confidence: Float = 1f
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val bodyWidthCoverage: Float get() = max(0f, right - left)
    val bodyHeightCoverage: Float get() = max(0f, bottom - top)

    fun toTargetResult() = TargetResult(
        found = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        centerX = centerX,
        centerY = centerY,
        coverage = bodyWidthCoverage,
        confidence = confidence,
        trackingMode = TrackingMode.PERSON,
        personTrackingId = trackingId,
        frameWidth = frameWidth,
        frameHeight = frameHeight
    )
}

data class PeopleResult(
    val candidates: List<PersonCandidate>,
    val frameWidth: Int,
    val frameHeight: Int
)
