package com.xtark.pilot.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class DriveJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    fun interface Listener {
        /** X: forward/back, Y: left/right strafe, normalized to -1..1. */
        fun onInput(linearX: Float, linearY: Float, active: Boolean)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val center = PointF()
    private val knob = PointF()
    private var radius = 0f
    private var active = false
    private var outputX = 0f
    private var outputY = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(63, 93, 111)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(43, 67, 82)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 207, 151)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(167, 185, 197)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!active) return
            listener?.onInput(outputX, outputY, true)
            postDelayed(this, HEARTBEAT_MS)
        }
    }

    init {
        isClickable = true
        contentDescription = "底盘平移摇杆"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        center.set(w / 2f, h / 2f)
        radius = (minOf(w, h) / 2f - 18f * density).coerceAtLeast(1f)
        if (!active) knob.set(center)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(center.x, center.y, radius, ringPaint)
        canvas.drawLine(center.x - radius, center.y, center.x + radius, center.y, crossPaint)
        canvas.drawLine(center.x, center.y - radius, center.x, center.y + radius, crossPaint)
        canvas.drawText("前进", center.x, center.y - radius + 15f * density, labelPaint)
        canvas.drawText("后退", center.x, center.y + radius - 7f * density, labelPaint)
        canvas.drawText("左移", center.x - radius + 23f * density, center.y + 4f * density, labelPaint)
        canvas.drawText("右移", center.x + radius - 23f * density, center.y + 4f * density, labelPaint)
        canvas.drawCircle(knob.x, knob.y, 19f * density, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                active = true
                removeCallbacks(heartbeat)
                updatePosition(event.x, event.y)
                postDelayed(heartbeat, HEARTBEAT_MS)
            }

            MotionEvent.ACTION_MOVE -> updatePosition(event.x, event.y)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                reset()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun reset() {
        active = false
        outputX = 0f
        outputY = 0f
        removeCallbacks(heartbeat)
        knob.set(center)
        listener?.onInput(0f, 0f, false)
        invalidate()
    }

    private fun updatePosition(x: Float, y: Float) {
        var dx = x - center.x
        var dy = y - center.y
        val distance = hypot(dx, dy)
        if (distance > radius) {
            val scale = radius / distance
            dx *= scale
            dy *= scale
        }
        knob.set(center.x + dx, center.y + dy)

        val normalizedX = dx / radius
        val normalizedY = dy / radius
        outputX = applyDeadZone(-normalizedY)
        // ROS/OpenCTR coordinates use +Y to the robot's left.
        outputY = applyDeadZone(-normalizedX)
        listener?.onInput(outputX, outputY, true)
        invalidate()
    }

    private fun applyDeadZone(value: Float): Float {
        if (kotlin.math.abs(value) < DEAD_ZONE) return 0f
        val magnitude = (kotlin.math.abs(value) - DEAD_ZONE) / (1f - DEAD_ZONE)
        return if (value < 0f) -magnitude else magnitude
    }

    override fun onDetachedFromWindow() {
        reset()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val HEARTBEAT_MS = 50L
        private const val DEAD_ZONE = 0.08f
    }
}
