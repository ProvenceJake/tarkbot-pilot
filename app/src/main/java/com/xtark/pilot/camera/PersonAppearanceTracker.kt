package com.xtark.pilot.camera

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class AppearanceVector(val bins: FloatArray) {
    init {
        require(bins.isNotEmpty())
    }

    fun similarity(other: AppearanceVector): Float {
        if (bins.size != other.bins.size) return 0f
        var intersection = 0f
        for (index in bins.indices) intersection += min(bins[index], other.bins[index])
        return intersection.coerceIn(0f, 1f)
    }

    fun blendedWith(other: AppearanceVector, alpha: Float): AppearanceVector {
        if (bins.size != other.bins.size) return this
        val safeAlpha = alpha.coerceIn(0f, 1f)
        return AppearanceVector(FloatArray(bins.size) { index ->
            bins[index] * (1f - safeAlpha) + other.bins[index] * safeAlpha
        })
    }
}

data class BodyObservation(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val appearance: AppearanceVector
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = max(0.001f, right - left)
    val height: Float get() = max(0.001f, bottom - top)
}

data class TrackedBody(
    val trackingId: Int,
    val observation: BodyObservation
)

/**
 * Keeps a stable local ID for detected people. The selected track is matched more strictly:
 * it must retain a similar clothing signature and plausible motion, so two crossing people do
 * not silently swap identities.
 */
class PersonAppearanceTracker {
    private data class Track(
        val id: Int,
        var observation: BodyObservation,
        var identityAppearance: AppearanceVector,
        var lastSeenMs: Long
    )

    private data class Match(val observationIndex: Int, val score: Float)

    private val tracks = mutableListOf<Track>()
    private var nextId = 1

    @Synchronized
    fun reset() {
        tracks.clear()
        nextId = 1
    }

    @Synchronized
    fun update(
        observations: List<BodyObservation>,
        nowMs: Long,
        selectedId: Int?
    ): List<TrackedBody> {
        tracks.removeAll { nowMs - it.lastSeenMs > TRACK_RETENTION_MS }
        val unmatched = observations.indices.toMutableSet()
        val output = mutableListOf<TrackedBody>()

        // Match the selected identity first so another track cannot consume its best body box.
        val orderedTracks = tracks.sortedByDescending { it.id == selectedId }
        orderedTracks.forEach { track ->
            val match = unmatched.mapNotNull { index ->
                matchScore(track, observations[index], track.id == selectedId)
                    ?.let { Match(index, it) }
            }.maxByOrNull(Match::score) ?: return@forEach

            val observation = observations[match.observationIndex]
            val appearanceSimilarity = track.identityAppearance.similarity(observation.appearance)
            val appearanceAlpha = when {
                track.id == selectedId && appearanceSimilarity >= SELECTED_UPDATE_SIMILARITY ->
                    SELECTED_APPEARANCE_ALPHA
                track.id != selectedId -> OTHER_APPEARANCE_ALPHA
                else -> 0f
            }
            if (appearanceAlpha > 0f) {
                track.identityAppearance = track.identityAppearance.blendedWith(
                    observation.appearance,
                    appearanceAlpha
                )
            }
            track.observation = observation
            track.lastSeenMs = nowMs
            unmatched.remove(match.observationIndex)
            output += TrackedBody(track.id, observation)
        }

        unmatched.forEach { index ->
            val observation = observations[index]
            val track = Track(nextId++, observation, observation.appearance, nowMs)
            tracks += track
            output += TrackedBody(track.id, observation)
        }

        return output.sortedByDescending { it.observation.confidence }
    }

    private fun matchScore(
        track: Track,
        current: BodyObservation,
        selected: Boolean
    ): Float? {
        val previous = track.observation
        val appearance = track.identityAppearance.similarity(current.appearance)
        val centerDistance = hypot(
            (current.centerX - previous.centerX).toDouble(),
            (current.centerY - previous.centerY).toDouble()
        ).toFloat()
        val widthRatio = current.width / previous.width
        val heightRatio = current.height / previous.height
        if (widthRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO ||
            heightRatio !in MIN_SIZE_RATIO..MAX_SIZE_RATIO
        ) return null

        val overlap = intersectionOverUnion(previous, current)
        val localMotion = overlap >= MIN_OVERLAP || centerDistance <= MAX_LOCAL_CENTER_DISTANCE
        if (!localMotion) return null
        if (selected && appearance < MIN_SELECTED_APPEARANCE) return null

        val motionScore = (1f - centerDistance / MAX_LOCAL_CENTER_DISTANCE).coerceIn(0f, 1f)
        val score = APPEARANCE_WEIGHT * appearance +
            MOTION_WEIGHT * motionScore +
            OVERLAP_WEIGHT * overlap
        val threshold = if (selected) SELECTED_MATCH_THRESHOLD else OTHER_MATCH_THRESHOLD
        return score.takeIf { it >= threshold }
    }

    private fun intersectionOverUnion(a: BodyObservation, b: BodyObservation): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val areaA = a.width * a.height
        val areaB = b.width * b.height
        return intersection / (areaA + areaB - intersection).coerceAtLeast(0.001f)
    }

    companion object {
        private const val TRACK_RETENTION_MS = 2_500L
        private const val MIN_SIZE_RATIO = 0.42f
        private const val MAX_SIZE_RATIO = 2.40f
        private const val MIN_OVERLAP = 0.025f
        private const val MAX_LOCAL_CENTER_DISTANCE = 0.30f
        private const val MIN_SELECTED_APPEARANCE = 0.36f
        private const val SELECTED_MATCH_THRESHOLD = 0.43f
        private const val OTHER_MATCH_THRESHOLD = 0.30f
        private const val SELECTED_UPDATE_SIMILARITY = 0.58f
        private const val SELECTED_APPEARANCE_ALPHA = 0.035f
        private const val OTHER_APPEARANCE_ALPHA = 0.14f
        private const val APPEARANCE_WEIGHT = 0.58f
        private const val MOTION_WEIGHT = 0.27f
        private const val OVERLAP_WEIGHT = 0.15f
    }
}
