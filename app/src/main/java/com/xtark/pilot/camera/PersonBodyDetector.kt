package com.xtark.pilot.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

class PersonBodyDetector(context: Context) : AutoCloseable {
    private val tracker = PersonAppearanceTracker()
    private var inferenceCount = 0L
    private val detector = ObjectDetector.createFromOptions(
        context.applicationContext,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setCategoryAllowlist(listOf("person"))
            .setScoreThreshold(MIN_CONFIDENCE)
            .setMaxResults(MAX_PEOPLE)
            .build()
    )

    fun detect(
        imageProxy: ImageProxy,
        selectedPersonId: Int?,
        nowMs: Long
    ): PeopleResult = detectBitmap(
        source = imageProxy.toBitmap(),
        rotationDegrees = imageProxy.imageInfo.rotationDegrees,
        selectedPersonId = selectedPersonId,
        nowMs = nowMs
    )

    internal fun detectBitmap(
        source: Bitmap,
        rotationDegrees: Int,
        selectedPersonId: Int?,
        nowMs: Long
    ): PeopleResult {
        val upright = rotate(source, rotationDegrees)
        var mpImage: MPImage? = null
        try {
            val inputImage = BitmapImageBuilder(upright).build()
            mpImage = inputImage
            val inferenceStartMs = SystemClock.elapsedRealtime()
            val result = detector.detect(inputImage)
            inferenceCount++
            if (inferenceCount == 1L || inferenceCount % PERFORMANCE_LOG_INTERVAL == 0L) {
                Log.i(
                    TAG,
                    "body inference=${SystemClock.elapsedRealtime() - inferenceStartMs}ms " +
                        "detections=${result.detections().size} frame=${upright.width}x${upright.height}"
                )
            }
            val width = upright.width.coerceAtLeast(1)
            val height = upright.height.coerceAtLeast(1)
            val observations = result.detections().mapNotNull { detection ->
                val box = detection.boundingBox()
                val left = (box.left / width).coerceIn(0f, 1f)
                val top = (box.top / height).coerceIn(0f, 1f)
                val right = (box.right / width).coerceIn(0f, 1f)
                val bottom = (box.bottom / height).coerceIn(0f, 1f)
                if (right - left < MIN_BODY_WIDTH || bottom - top < MIN_BODY_HEIGHT) {
                    return@mapNotNull null
                }
                BodyObservation(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    confidence = detection.categories().firstOrNull()?.score() ?: 0f,
                    appearance = BodyAppearanceExtractor.extract(
                        upright,
                        left,
                        top,
                        right,
                        bottom
                    )
                )
            }
            val candidates = tracker.update(observations, nowMs, selectedPersonId).map { tracked ->
                val body = tracked.observation
                PersonCandidate(
                    trackingId = tracked.trackingId,
                    left = body.left,
                    top = body.top,
                    right = body.right,
                    bottom = body.bottom,
                    frameWidth = width,
                    frameHeight = height,
                    confidence = body.confidence
                )
            }
            return PeopleResult(candidates, width, height)
        } finally {
            // BitmapImageBuilder does not copy the bitmap. Closing MPImage recycles it, so the
            // image must stay open until clothing features have been extracted from detections.
            mpImage?.close()
            if (mpImage == null && !upright.isRecycled) upright.recycle()
            if (source !== upright && !source.isRecycled) source.recycle()
        }
    }

    @Synchronized
    fun reset() {
        tracker.reset()
    }

    override fun close() {
        detector.close()
    }

    private fun rotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    companion object {
        private const val MODEL_ASSET = "efficientdet_lite0_int8.tflite"
        private const val MIN_CONFIDENCE = 0.34f
        private const val MAX_PEOPLE = 6
        private const val MIN_BODY_WIDTH = 0.040f
        private const val MIN_BODY_HEIGHT = 0.11f
        private const val PERFORMANCE_LOG_INTERVAL = 120L
        private const val TAG = "TarkbotPerson"
    }
}
