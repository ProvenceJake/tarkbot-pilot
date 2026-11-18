package com.xtark.pilot.camera

import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min

data class TargetResult(
    val found: Boolean,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val coverage: Float = 0f,
    val confidence: Float = 0f,
    val trackingMode: TrackingMode = TrackingMode.COLOR,
    val personTrackingId: Int? = null,
    val targetColor: TargetColor = TargetColor.RED,
    val frameWidth: Int = 1,
    val frameHeight: Int = 1
)

class ColorTargetDetector {
    fun detect(image: ImageProxy, targetColor: TargetColor): TargetResult {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val sampleStep = 6
        val columns = (width + sampleStep - 1) / sampleStep
        val rows = (height + sampleStep - 1) / sampleStep
        val matching = BooleanArray(columns * rows)
        var samples = 0

        for (gridY in 0 until rows) {
            val y = min(gridY * sampleStep, height - 1)
            for (gridX in 0 until columns) {
                val x = min(gridX * sampleStep, width - 1)
                val yIndex = y * yPlane.rowStride + x * yPlane.pixelStride
                val uvX = x / 2
                val uvY = y / 2
                val uIndex = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                val vIndex = uvY * vPlane.rowStride + uvX * vPlane.pixelStride
                if (yIndex < yBuffer.limit() && uIndex < uBuffer.limit() && vIndex < vBuffer.limit()) {
                    val yValue = yBuffer.get(yIndex).toInt() and 0xff
                    val uValue = uBuffer.get(uIndex).toInt() and 0xff
                    val vValue = vBuffer.get(vIndex).toInt() and 0xff
                    val rgb = yuvToRgb(yValue, uValue, vValue)
                    if (matchesColor(
                            rgb shr 16 and 0xFF,
                            rgb shr 8 and 0xFF,
                            rgb and 0xFF,
                            targetColor
                        )
                    ) {
                        matching[gridY * columns + gridX] = true
                    }
                    samples++
                }
            }
        }

        val component = largestComponent(matching, columns, rows)
        val coverage = if (samples == 0) 0f else component.count.toFloat() / samples
        val minimumMatches = max(16, (samples * 0.0025f).toInt())
        if (component.count < minimumMatches) {
            return TargetResult(
                found = false,
                targetColor = targetColor,
                frameWidth = rotatedWidth(image),
                frameHeight = rotatedHeight(image)
            )
        }

        val minX = component.minX * sampleStep
        val minY = component.minY * sampleStep
        val maxX = min(width - 1, (component.maxX + 1) * sampleStep)
        val maxY = min(height - 1, (component.maxY + 1) * sampleStep)

        val normalized = rotateRect(
            minX.toFloat() / width,
            minY.toFloat() / height,
            maxX.toFloat() / width,
            maxY.toFloat() / height,
            image.imageInfo.rotationDegrees
        )
        return TargetResult(
            found = true,
            left = normalized[0],
            top = normalized[1],
            right = normalized[2],
            bottom = normalized[3],
            centerX = (normalized[0] + normalized[2]) / 2f,
            centerY = (normalized[1] + normalized[3]) / 2f,
            coverage = coverage,
            confidence = 1f,
            targetColor = targetColor,
            frameWidth = rotatedWidth(image),
            frameHeight = rotatedHeight(image)
        )
    }

    private fun rotatedWidth(image: ImageProxy): Int =
        if (image.imageInfo.rotationDegrees % 180 == 0) image.width else image.height

    private fun rotatedHeight(image: ImageProxy): Int =
        if (image.imageInfo.rotationDegrees % 180 == 0) image.height else image.width

    private fun rotateRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotation: Int
    ): FloatArray = when (rotation) {
        90 -> floatArrayOf(1f - bottom, left, 1f - top, right)
        180 -> floatArrayOf(1f - right, 1f - bottom, 1f - left, 1f - top)
        270 -> floatArrayOf(top, 1f - right, bottom, 1f - left)
        else -> floatArrayOf(left, top, right, bottom)
    }

    private data class Component(
        val count: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private fun largestComponent(
        matching: BooleanArray,
        columns: Int,
        rows: Int
    ): Component {
        val visited = BooleanArray(matching.size)
        val queue = IntArray(matching.size)
        var best = Component(0, 0, 0, 0, 0)

        for (start in matching.indices) {
            if (!matching[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var count = 0
            var minX = columns
            var minY = rows
            var maxX = 0
            var maxY = 0

            while (head < tail) {
                val index = queue[head++]
                val x = index % columns
                val y = index / columns
                count++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        val nextY = y + dy
                        if (nextX !in 0 until columns || nextY !in 0 until rows) continue
                        val next = nextY * columns + nextX
                        if (matching[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }

            if (count > best.count) best = Component(count, minX, minY, maxX, maxY)
        }
        return best
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Int {
        val c = max(0, y - 16)
        val d = u - 128
        val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun matchesColor(r: Int, g: Int, b: Int, target: TargetColor): Boolean {
        val brightness = max(r, max(g, b))
        val darkest = min(r, min(g, b))
        val saturation = if (brightness == 0) 0f else (brightness - darkest) / brightness.toFloat()
        if (brightness < 70 || saturation < 0.32f) return false
        return when (target) {
            TargetColor.RED ->
                r >= 100 && r - g >= 45 && r - b >= 35 && g < r * 0.70f
            TargetColor.GREEN ->
                g >= 85 && g - r >= 32 && g - b >= 24 && r < g * 0.78f
            TargetColor.BLUE ->
                b >= 90 && b - r >= 32 && b - g >= 28 && g < b * 0.78f
        }
    }
}
