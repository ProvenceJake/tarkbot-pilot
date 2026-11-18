package com.xtark.pilot.control

import kotlin.math.abs
import kotlin.math.sign

class TargetFollowController {
    private var initialized = false
    private var filteredCenterX = 0.5
    private var filteredCoverage = 0.0
    private var turnActive = false
    private var distanceDirection = 0
    private var lastSetpoint = MotionSetpoint.STOPPED

    @Synchronized
    fun reset() {
        resetInternal()
    }

    @Synchronized
    fun evaluate(
        targetFound: Boolean,
        frameAgeMs: Long,
        centerX: Float,
        coverage: Float,
        desiredCoverage: Double,
        userLinearLimitMps: Double,
        allowReverse: Boolean
    ): MotionSetpoint {
        if (!targetFound || frameAgeMs !in 0..FRAME_TIMEOUT_MS) {
            resetInternal()
            return MotionSetpoint.STOPPED
        }

        val measuredCenter = centerX.coerceIn(0f, 1f).toDouble()
        val measuredCoverage = coverage.coerceIn(0f, 1f).toDouble()
        if (!initialized) {
            filteredCenterX = measuredCenter
            filteredCoverage = measuredCoverage
            initialized = true
        } else {
            filteredCenterX += CENTER_FILTER_ALPHA * (measuredCenter - filteredCenterX)
            filteredCoverage += COVERAGE_FILTER_ALPHA * (measuredCoverage - filteredCoverage)
        }

        val horizontalError = 0.5 - filteredCenterX
        val absHorizontalError = abs(horizontalError)
        val rawHorizontalError = abs(0.5 - measuredCenter)
        turnActive = if (turnActive) {
            absHorizontalError > CENTER_EXIT_DEAD_ZONE
        } else {
            absHorizontalError > CENTER_ENTER_DEAD_ZONE
        }
        val requestedAngular = if (turnActive) {
            val proportional = (horizontalError * TURN_GAIN)
                .coerceIn(-MAX_TURN_RAD_S, MAX_TURN_RAD_S)
            proportional.sign * maxOf(abs(proportional), MIN_EFFECTIVE_TURN_RAD_S)
        } else {
            0.0
        }

        val desired = desiredCoverage.coerceIn(0.01, 0.50)
        val sizeError = desired - filteredCoverage
        // A large relative dead zone made person following stop visibly before the requested
        // body width. Use a tighter hysteresis band and keep a small effective crawl command so
        // the mecanum wheels can overcome low-speed stiction during the final approach.
        val distanceEnterDeadZone = maxOf(MIN_DISTANCE_ENTER_DEAD_ZONE, desired * 0.08)
        val distanceExitDeadZone = distanceEnterDeadZone * 0.45
        distanceDirection = when {
            distanceDirection == 0 && abs(sizeError) > distanceEnterDeadZone ->
                sizeError.sign.toInt()
            distanceDirection != 0 && abs(sizeError) < distanceExitDeadZone -> 0
            distanceDirection != 0 && sizeError.sign.toInt() != distanceDirection &&
                abs(sizeError) > distanceEnterDeadZone -> sizeError.sign.toInt()
            else -> distanceDirection
        }

        val maxForward = userLinearLimitMps.coerceIn(0.0, MAX_FOLLOW_LINEAR_MPS)
        var forceLinearStop = distanceDirection == 0 ||
            (distanceDirection < 0 && !allowReverse)
        var requestedLinear = when (distanceDirection) {
            1 -> maxOf(
                (sizeError * DISTANCE_GAIN).coerceIn(0.0, maxForward),
                minOf(MIN_EFFECTIVE_FORWARD_MPS, maxForward)
            )
            -1 -> if (allowReverse) {
                (sizeError * DISTANCE_GAIN).coerceIn(-MAX_REVERSE_MPS, 0.0)
            } else {
                0.0
            }
            else -> 0.0
        }

        // Filtering smooths detection noise but lags behind a rapidly growing body box. Apply
        // the current measurement as a one-sided close-range brake for person following.
        if (!allowReverse && measuredCoverage >= desired * PERSON_RAW_STOP_RATIO) {
            distanceDirection = 0
            requestedLinear = 0.0
            forceLinearStop = true
        }

        // Do not translate while a target is far off-axis. Inside that threshold,
        // progressively slow forward motion as the steering error grows.
        if (absHorizontalError >= TURN_IN_PLACE_THRESHOLD ||
            rawHorizontalError >= RAW_TURN_IN_PLACE_THRESHOLD
        ) {
            requestedLinear = 0.0
            forceLinearStop = true
        } else if (requestedLinear > 0.0) {
            val steeringScale = ((TURN_IN_PLACE_THRESHOLD - absHorizontalError) /
                (TURN_IN_PLACE_THRESHOLD - CENTER_EXIT_DEAD_ZONE)).coerceIn(0.25, 1.0)
            requestedLinear *= steeringScale
        }

        val linearStep = if (abs(requestedLinear) < abs(lastSetpoint.linearX)) {
            MAX_LINEAR_DECEL_PER_TICK
        } else {
            MAX_LINEAR_ACCEL_PER_TICK
        }
        val next = MotionSetpoint(
            linearX = if (forceLinearStop) {
                0.0
            } else {
                moveToward(lastSetpoint.linearX, requestedLinear, linearStep)
            },
            linearY = 0.0,
            angularZ = moveToward(
                lastSetpoint.angularZ,
                requestedAngular,
                if (abs(requestedAngular) < abs(lastSetpoint.angularZ)) {
                    MAX_ANGULAR_DECEL_PER_TICK
                } else {
                    MAX_ANGULAR_ACCEL_PER_TICK
                }
            )
        )
        lastSetpoint = next
        return next
    }

    private fun resetInternal() {
        initialized = false
        filteredCenterX = 0.5
        filteredCoverage = 0.0
        turnActive = false
        distanceDirection = 0
        lastSetpoint = MotionSetpoint.STOPPED
    }

    private fun moveToward(current: Double, target: Double, maxStep: Double): Double = when {
        target > current -> minOf(target, current + maxStep)
        target < current -> maxOf(target, current - maxStep)
        else -> target
    }

    companion object {
        const val FRAME_TIMEOUT_MS = 300L
        private const val CENTER_ENTER_DEAD_ZONE = 0.065
        private const val CENTER_EXIT_DEAD_ZONE = 0.040
        private const val TURN_IN_PLACE_THRESHOLD = 0.22
        private const val RAW_TURN_IN_PLACE_THRESHOLD = 0.28
        private const val MIN_DISTANCE_ENTER_DEAD_ZONE = 0.006
        private const val TURN_GAIN = 1.35
        private const val DISTANCE_GAIN = 2.20
        private const val CENTER_FILTER_ALPHA = 0.38
        private const val COVERAGE_FILTER_ALPHA = 0.25
        private const val MAX_FOLLOW_LINEAR_MPS = 0.28
        private const val MAX_REVERSE_MPS = 0.08
        private const val MAX_TURN_RAD_S = 0.50
        private const val MIN_EFFECTIVE_FORWARD_MPS = 0.045
        private const val MIN_EFFECTIVE_TURN_RAD_S = 0.12
        private const val PERSON_RAW_STOP_RATIO = 1.03
        private const val MAX_LINEAR_ACCEL_PER_TICK = 0.018
        private const val MAX_LINEAR_DECEL_PER_TICK = 0.040
        private const val MAX_ANGULAR_ACCEL_PER_TICK = 0.060
        private const val MAX_ANGULAR_DECEL_PER_TICK = 0.120
    }
}
