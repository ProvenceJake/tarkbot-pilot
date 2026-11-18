package com.xtark.pilot.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class TargetOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }

    @Volatile
    private var target: TargetResult? = null
    @Volatile
    private var people: PeopleResult? = null
    @Volatile
    private var selectedPersonId: Int? = null
    private var personSelectionListener: ((Int) -> Unit)? = null

    fun setOnPersonSelectedListener(listener: (Int) -> Unit) {
        personSelectionListener = listener
    }

    fun updateTarget(result: TargetResult) {
        target = result
        if (result.trackingMode == TrackingMode.COLOR) people = null
        postInvalidateOnAnimation()
    }

    fun updatePeople(result: PeopleResult, selectedId: Int?) {
        people = result
        selectedPersonId = selectedId
        target = null
        postInvalidateOnAnimation()
    }

    fun clearPeople() {
        people = null
        selectedPersonId = null
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCenterGuide(canvas)

        val peopleSnapshot = people
        if (peopleSnapshot != null) {
            drawPeople(canvas, peopleSnapshot)
            return
        }

        val result = target ?: return
        if (!result.found) return
        val rect = renderRect(
            result.left,
            result.top,
            result.right,
            result.bottom,
            result.frameWidth,
            result.frameHeight
        )
        val color = when (result.targetColor) {
            TargetColor.RED -> Color.rgb(255, 88, 88)
            TargetColor.GREEN -> Color.rgb(46, 214, 161)
            TargetColor.BLUE -> Color.rgb(93, 184, 255)
        }
        boxPaint.color = color
        boxPaint.strokeWidth = 5f
        centerPaint.color = color
        canvas.drawRoundRect(rect, 18f, 18f, boxPaint)
        canvas.drawCircle(rect.centerX(), rect.centerY(), min(13f, rect.width() / 4f), centerPaint)
    }

    private fun drawCenterGuide(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 28f, guidePaint)
        canvas.drawLine(cx - 45f, cy, cx + 45f, cy, guidePaint)
        canvas.drawLine(cx, cy - 45f, cx, cy + 45f, guidePaint)
    }

    private fun drawPeople(canvas: Canvas, result: PeopleResult) {
        result.candidates.forEach { candidate ->
            val selected = candidate.trackingId == selectedPersonId
            val rect = renderRect(
                candidate.left,
                candidate.top,
                candidate.right,
                candidate.bottom,
                result.frameWidth,
                result.frameHeight
            )
            val color = if (selected) {
                Color.rgb(46, 214, 161)
            } else {
                Color.rgb(255, 193, 71)
            }
            boxPaint.color = color
            boxPaint.strokeWidth = if (selected) 8f else 4f
            centerPaint.color = color
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint)
            canvas.drawCircle(rect.centerX(), rect.centerY(), min(11f, rect.width() / 4f), centerPaint)
            labelPaint.color = color
            val label = if (selected) "已锁定" else "点击换人"
            canvas.drawText(label, rect.left, (rect.top - 10f).coerceAtLeast(32f), labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val result = people ?: return false
        if (result.candidates.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val point = normalizedPoint(event.x, event.y, result.frameWidth, result.frameHeight)
                    ?: return true
                val containing = result.candidates
                    .filter {
                        point.first >= it.left - TAP_PADDING &&
                            point.first <= it.right + TAP_PADDING &&
                            point.second >= it.top - TAP_PADDING &&
                            point.second <= it.bottom + TAP_PADDING
                    }
                    .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
                val candidate = containing ?: result.candidates.minByOrNull {
                    val dx = it.centerX - point.first
                    val dy = it.centerY - point.second
                    dx * dx + dy * dy
                }?.takeIf {
                    val dx = it.centerX - point.first
                    val dy = it.centerY - point.second
                    dx * dx + dy * dy <= MAX_TAP_DISTANCE * MAX_TAP_DISTANCE
                }
                candidate?.let { personSelectionListener?.invoke(it.trackingId) }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun renderRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        frameWidth: Int,
        frameHeight: Int
    ): RectF {
        val geometry = renderGeometry(frameWidth, frameHeight)
        return RectF(
            geometry.offsetX + left * geometry.renderWidth,
            geometry.offsetY + top * geometry.renderHeight,
            geometry.offsetX + right * geometry.renderWidth,
            geometry.offsetY + bottom * geometry.renderHeight
        )
    }

    private fun normalizedPoint(
        x: Float,
        y: Float,
        frameWidth: Int,
        frameHeight: Int
    ): Pair<Float, Float>? {
        val geometry = renderGeometry(frameWidth, frameHeight)
        if (x < geometry.offsetX || x > geometry.offsetX + geometry.renderWidth) return null
        if (y < geometry.offsetY || y > geometry.offsetY + geometry.renderHeight) return null
        return Pair(
            ((x - geometry.offsetX) / geometry.renderWidth).coerceIn(0f, 1f),
            ((y - geometry.offsetY) / geometry.renderHeight).coerceIn(0f, 1f)
        )
    }

    private fun renderGeometry(frameWidth: Int, frameHeight: Int): RenderGeometry {
        val frameAspect = frameWidth.coerceAtLeast(1).toFloat() / frameHeight.coerceAtLeast(1)
        val viewAspect = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
        val renderWidth: Float
        val renderHeight: Float
        if (frameAspect > viewAspect) {
            renderWidth = width.toFloat()
            renderHeight = width / frameAspect
        } else {
            renderHeight = height.toFloat()
            renderWidth = height * frameAspect
        }
        return RenderGeometry(
            renderWidth,
            renderHeight,
            (width - renderWidth) / 2f,
            (height - renderHeight) / 2f
        )
    }

    private data class RenderGeometry(
        val renderWidth: Float,
        val renderHeight: Float,
        val offsetX: Float,
        val offsetY: Float
    )

    companion object {
        private const val TAP_PADDING = 0.035f
        private const val MAX_TAP_DISTANCE = 0.14f
    }
}
