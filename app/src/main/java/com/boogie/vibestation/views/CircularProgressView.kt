package com.boogie.vibestation.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Custom circular progress ring displaying media playback advancement.
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = DEFAULT_STROKE_WIDTH
        strokeCap = Paint.Cap.ROUND
        color = DEFAULT_COLOR
    }
    private val rect = RectF()

    /** Completion fraction, clamped to [0.0, 1.0]; assigning triggers view invalidation. */
    var progress: Float = 0f
        set(value) {
            field = clampProgress(value)
            invalidate()
        }

    /** ARGB stroke color; assigning triggers view invalidation. */
    var color: Int = DEFAULT_COLOR
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = paint.strokeWidth / 2f
        rect.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Start arc at 90 degrees (6 o'clock position)
        canvas.drawArc(rect, START_ANGLE, calculateSweepAngle(progress), false, paint)
    }

    companion object {
        const val DEFAULT_STROKE_WIDTH = 12f
        const val START_ANGLE = 90f
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()

        /**
         * Clamps fractional progress within normalized bounds [0.0, 1.0].
         *
         * @param progress Raw progress input value.
         * @return Clamped progress in the range 0.0f to 1.0f inclusive.
         */
        fun clampProgress(progress: Float): Float = progress.coerceIn(0f, 1f)

        /**
         * Computes arc sweep angle in degrees for the given progress value.
         *
         * @param progress Progress fractional value [0.0, 1.0].
         * @return Sweep angle from 0.0f to 360.0f degrees.
         */
        fun calculateSweepAngle(progress: Float): Float = clampProgress(progress) * 360f
    }
}
