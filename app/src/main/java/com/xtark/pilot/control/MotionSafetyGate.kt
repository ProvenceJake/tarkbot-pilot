package com.xtark.pilot.control

import com.xtark.pilot.protocol.XProtocolEncoder

data class MotionSetpoint(
    val linearX: Double,
    val linearY: Double,
    val angularZ: Double
) {
    companion object {
        val STOPPED = MotionSetpoint(0.0, 0.0, 0.0)
    }
}

object MotionSafetyGate {
    const val INPUT_TIMEOUT_MS = 160L

    fun evaluate(
        armed: Boolean,
        connected: Boolean,
        inputActive: Boolean,
        heartbeatAgeMs: Long,
        normalizedX: Float,
        normalizedY: Float,
        turnDirection: Int,
        maxLinearMps: Double,
        angularRadS: Double
    ): MotionSetpoint {
        val freshInput = inputActive && heartbeatAgeMs in 0..INPUT_TIMEOUT_MS
        if (!armed || !connected || !freshInput) return MotionSetpoint.STOPPED

        val safeLinearLimit = maxLinearMps.coerceIn(0.0, XProtocolEncoder.MAX_LINEAR_MPS)
        val safeAngular = angularRadS.coerceIn(
            -XProtocolEncoder.MAX_ANGULAR_RAD_S,
            XProtocolEncoder.MAX_ANGULAR_RAD_S
        )
        return MotionSetpoint(
            linearX = normalizedX.coerceIn(-1f, 1f) * safeLinearLimit,
            linearY = normalizedY.coerceIn(-1f, 1f) * safeLinearLimit,
            angularZ = turnDirection.coerceIn(-1, 1) * safeAngular
        )
    }
}
