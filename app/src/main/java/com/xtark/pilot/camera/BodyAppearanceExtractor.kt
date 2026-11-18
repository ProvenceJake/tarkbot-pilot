package com.xtark.pilot.camera

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

object BodyAppearanceExtractor {
    internal const val COLOR_BINS_PER_PART = 16
    private const val PARTS = 2
    private const val HUE_BINS = 12
    private const val GRAY_BINS = 4

    fun extract(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): AppearanceVector {
        check(!bitmap.isRecycled) { "人体外观图像已被提前释放" }
        require(bitmap.width > 0 && bitmap.height > 0) { "人体外观图像尺寸无效" }
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "人体框包含无效坐标"
        }
        val region = appearanceRegion(
            bitmap.width,
            bitmap.height,
            left,
            top,
            right,
            bottom
        )
        // Keep the central torso and upper legs; this avoids most background and head changes.
        val roiWidth = region.right - region.left
        val roiHeight = region.bottom - region.top
        val pixels = IntArray(roiWidth * roiHeight)
        bitmap.getPixels(
            pixels,
            0,
            roiWidth,
            region.left,
            region.top,
            roiWidth,
            roiHeight
        )

        val bins = FloatArray(COLOR_BINS_PER_PART * PARTS)
        val sampleStep = max(1, min(roiWidth, roiHeight) / 36)
        val middle = roiHeight * 46 / 100
        for (y in 0 until roiHeight step sampleStep) {
            val partOffset = if (y < middle) 0 else COLOR_BINS_PER_PART
            for (x in 0 until roiWidth step sampleStep) {
                val color = pixels[y * roiWidth + x]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                bins[partOffset + colorBin(red, green, blue)]++
            }
        }

        normalizePart(bins, 0)
        normalizePart(bins, COLOR_BINS_PER_PART)
        return AppearanceVector(bins)
    }

    internal data class PixelRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

    internal fun appearanceRegion(
        bitmapWidth: Int,
        bitmapHeight: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): PixelRegion {
        require(bitmapWidth > 0 && bitmapHeight > 0)
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        val safeLeft = min(left, right).coerceIn(0f, 1f)
        val safeRight = max(left, right).coerceIn(0f, 1f)
        val safeTop = min(top, bottom).coerceIn(0f, 1f)
        val safeBottom = max(top, bottom).coerceIn(0f, 1f)
        val boxWidth = ((safeRight - safeLeft) * bitmapWidth).coerceAtLeast(1f)
        val boxHeight = ((safeBottom - safeTop) * bitmapHeight).coerceAtLeast(1f)
        val roiLeft = ((safeLeft * bitmapWidth) + boxWidth * 0.18f).toInt()
            .coerceIn(0, bitmapWidth - 1)
        val roiRight = ((safeLeft * bitmapWidth) + boxWidth * 0.82f).toInt()
            .coerceIn(roiLeft + 1, bitmapWidth)
        val roiTop = ((safeTop * bitmapHeight) + boxHeight * 0.16f).toInt()
            .coerceIn(0, bitmapHeight - 1)
        val roiBottom = ((safeTop * bitmapHeight) + boxHeight * 0.76f).toInt()
            .coerceIn(roiTop + 1, bitmapHeight)
        return PixelRegion(roiLeft, roiTop, roiRight, roiBottom)
    }

    internal fun colorBin(red: Int, green: Int, blue: Int): Int {
        val brightest = max(red, max(green, blue))
        val darkest = min(red, min(green, blue))
        val delta = brightest - darkest
        val saturation = if (brightest == 0) 0f else delta / brightest.toFloat()
        if (saturation < 0.18f || delta == 0) {
            return HUE_BINS + (brightest * GRAY_BINS / 256).coerceIn(0, GRAY_BINS - 1)
        }

        val hueSection = when (brightest) {
            red -> ((green - blue) / delta.toFloat()) % 6f
            green -> (blue - red) / delta.toFloat() + 2f
            else -> (red - green) / delta.toFloat() + 4f
        }
        val hue = (hueSection * 60f + 360f) % 360f
        return (hue / (360f / HUE_BINS)).toInt().coerceIn(0, HUE_BINS - 1)
    }

    private fun normalizePart(bins: FloatArray, offset: Int) {
        var total = 0f
        for (index in offset until offset + COLOR_BINS_PER_PART) total += bins[index]
        if (total <= 0f) return
        for (index in offset until offset + COLOR_BINS_PER_PART) {
            // Each body section contributes equally to the final histogram intersection.
            bins[index] = bins[index] / total / PARTS
        }
    }
}
