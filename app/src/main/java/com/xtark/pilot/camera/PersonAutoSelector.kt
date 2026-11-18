package com.xtark.pilot.camera

import kotlin.math.abs

/**
 * Chooses a stable initial person lock without moving the robot. A candidate must remain the
 * preferred person for multiple detector updates so a passer-by cannot steal the initial lock.
 */
class PersonAutoSelector(
    private val requiredObservations: Int = DEFAULT_REQUIRED_OBSERVATIONS
) {
    init {
        require(requiredObservations > 0)
    }

    private var pendingId: Int? = null
    private var pendingObservations = 0

    @Synchronized
    fun reset() {
        pendingId = null
        pendingObservations = 0
    }

    @Synchronized
    fun observe(candidates: List<PersonCandidate>): PersonCandidate? {
        if (candidates.isEmpty()) {
            reset()
            return null
        }

        val scored = candidates.associateWith(::score)
        val best = scored.maxByOrNull { it.value }!!.key
        val pending = candidates.firstOrNull { it.trackingId == pendingId }
        val preferred = if (pending != null &&
            scored.getValue(best) < scored.getValue(pending) + SWITCH_MARGIN
        ) {
            pending
        } else {
            best
        }

        if (preferred.trackingId == pendingId) {
            pendingObservations++
        } else {
            pendingId = preferred.trackingId
            pendingObservations = 1
        }
        return preferred.takeIf { pendingObservations >= requiredObservations }
    }

    internal fun score(candidate: PersonCandidate): Float {
        val horizontalCenter = (1f - abs(candidate.centerX - 0.5f) * 2f).coerceIn(0f, 1f)
        val verticalCenter = (1f - abs(candidate.centerY - 0.5f) * 2f).coerceIn(0f, 1f)
        val visibleBody = (
            candidate.bodyHeightCoverage.coerceAtMost(0.75f) / 0.75f * 0.65f +
                candidate.bodyWidthCoverage.coerceAtMost(0.45f) / 0.45f * 0.35f
            ).coerceIn(0f, 1f)
        val borderContacts = listOf(
            candidate.left <= BORDER_MARGIN,
            candidate.top <= BORDER_MARGIN,
            candidate.right >= 1f - BORDER_MARGIN,
            candidate.bottom >= 1f - BORDER_MARGIN
        ).count { it }
        return HORIZONTAL_WEIGHT * horizontalCenter +
            VERTICAL_WEIGHT * verticalCenter +
            BODY_WEIGHT * visibleBody +
            CONFIDENCE_WEIGHT * candidate.confidence.coerceIn(0f, 1f) -
            borderContacts * BORDER_PENALTY
    }

    companion object {
        private const val DEFAULT_REQUIRED_OBSERVATIONS = 2
        private const val SWITCH_MARGIN = 0.12f
        private const val BORDER_MARGIN = 0.012f
        private const val BORDER_PENALTY = 0.045f
        private const val HORIZONTAL_WEIGHT = 0.50f
        private const val VERTICAL_WEIGHT = 0.10f
        private const val BODY_WEIGHT = 0.24f
        private const val CONFIDENCE_WEIGHT = 0.16f
    }
}
