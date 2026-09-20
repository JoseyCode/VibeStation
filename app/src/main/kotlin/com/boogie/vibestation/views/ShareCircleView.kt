package com.boogie.vibestation.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.boogie.vibestation.share.GestureOutcome
import com.boogie.vibestation.share.ShareGesture
import com.boogie.vibestation.share.ShareScreenText
import com.boogie.vibestation.share.ShareState
import com.boogie.vibestation.share.allowsNo
import com.boogie.vibestation.share.allowsYes
import kotlin.math.abs
import kotlin.math.sin

/**
 * The whole Share Mode screen: one circle you hold and drag. Up means yes (send, accept, confirm the code),
 * down means no (close, decline, reject); sideways does nothing. The circle changes colour with the state
 * and with how far it is dragged, and the words for the state are drawn on the canvas. It only draws and
 * reports the user's decision; what happens is up to whoever sets [onYes] and [onNo].
 */
@Suppress("TooManyFunctions") // one view: touch, colour and draw helpers belong together
class ShareCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = textPaint(TITLE_SP, bold = true)
    private val detailPaint = textPaint(DETAIL_SP, bold = false)
    private val hintPaint = textPaint(HINT_SP, bold = false).apply { alpha = HINT_ALPHA }
    private val ringBounds = RectF()
    private var gesture = ShareGesture(1f)

    /** What Share Mode is doing; assigning redraws and makes any drag that began earlier count for nothing. */
    internal var shareState: ShareState = ShareState.Idle
        set(value) {
            val changed = field::class != value::class
            field = value
            gesture.allowYes = value.allowsYes
            gesture.allowNo = value.allowsNo
            if (changed) gesture.invalidate()
            contentDescription = ShareScreenText.of(value).let { "${it.title}. ${it.detail}" }
            invalidate()
        }

    /** Called when the user commits to yes by dragging up past the threshold and letting go. */
    internal var onYes: (() -> Unit)? = null

    /** Called when the user commits to no by dragging down past the threshold and letting go. */
    internal var onNo: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gesture = ShareGesture(h * TRAVEL_FRACTION).also {
            it.allowYes = shareState.allowsYes
            it.allowNo = shareState.allowsNo
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchesCircle(event) && gesture.down(event.y).active
            MotionEvent.ACTION_MOVE -> gesture.state.active.also { gesture.move(event.y) }
            MotionEvent.ACTION_UP -> gesture.state.active.also { finish(gesture.up()) }
            MotionEvent.ACTION_CANCEL -> gesture.cancel().let { false }
            else -> false
        }
        invalidate()
        return handled
    }

    private fun touchesCircle(event: MotionEvent): Boolean {
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val reach = baseRadius() * TOUCH_SLACK
        return dx * dx + dy * dy <= reach * reach
    }

    private fun finish(outcome: GestureOutcome) {
        when (outcome) {
            GestureOutcome.YES -> {
                performClick()
                onYes?.invoke()
            }
            GestureOutcome.NO -> {
                performClick()
                onNo?.invoke()
            }
            GestureOutcome.NONE -> Unit
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val drag = gesture.state
        val time = SystemClock.uptimeMillis()
        val cx = width / 2f
        val cy = height / 2f - drag.progress * height * TRAVEL_FRACTION * FOLLOW
        val pulse = 1f + PULSE * sin(time / PULSE_PERIOD_MS)
        val radius = baseRadius() * pulse * (1f + drag.progress.let(::abs) * GROW)

        circlePaint.color = circleColor(time, drag.progress)
        canvas.drawCircle(cx, cy, radius, circlePaint)
        drawProgressRing(canvas, cx, cy, radius)
        drawText(canvas, cx, drag.active)
        postInvalidateOnAnimation()
    }

    private fun drawProgressRing(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val fraction = ShareScreenText.progress(shareState) ?: return
        ringPaint.strokeWidth = radius * RING_WIDTH
        ringPaint.color = Color.WHITE
        val outer = radius * RING_GAP
        ringBounds.set(cx - outer, cy - outer, cx + outer, cy + outer)
        canvas.drawArc(ringBounds, START_ANGLE, fraction * FULL_TURN, false, ringPaint)
    }

    private fun drawText(canvas: Canvas, cx: Float, dragging: Boolean) {
        val text = ShareScreenText.of(shareState)
        val room = width * TEXT_WIDTH
        var y = height * TEXT_TOP
        canvas.drawText(fit(text.title, titlePaint, room), cx, y, titlePaint)
        y += titlePaint.textSize * LINE_GAP
        canvas.drawText(fit(text.detail, detailPaint, room), cx, y, detailPaint)
        if (dragging) canvas.drawText(fit(text.hint, hintPaint, room), cx, height * HINT_TOP, hintPaint)
    }

    private fun circleColor(time: Long, drag: Float): Int {
        val base = when (shareState) {
            is ShareState.Idle, is ShareState.Searching ->
                Color.HSVToColor(floatArrayOf((time / RAINBOW_MS_PER_DEGREE) % FULL_TURN, SATURATION, 1f))
            is ShareState.Connecting, is ShareState.Verifying -> BLUE
            is ShareState.IncomingOffer -> AMBER
            is ShareState.Closed -> GREY
            else -> GREEN
        }
        val target = if (drag >= 0f) GREEN else RED
        return ColorUtils.blendARGB(base, target, abs(drag))
    }

    private fun baseRadius() = minOf(width, height) * RADIUS_FRACTION

    private fun fit(text: String, paint: TextPaint, room: Float): String =
        TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()

    private fun textPaint(sp: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp * resources.displayMetrics.scaledDensity
        isFakeBoldText = bold
    }

    private companion object {
        const val TITLE_SP = 24f
        const val DETAIL_SP = 16f
        const val HINT_SP = 14f
        const val HINT_ALPHA = 200
        const val TRAVEL_FRACTION = 0.35f
        const val FOLLOW = 0.5f
        const val RADIUS_FRACTION = 0.22f
        const val TOUCH_SLACK = 1.3f
        const val GROW = 0.15f
        const val PULSE = 0.04f
        const val PULSE_PERIOD_MS = 400f
        const val RING_WIDTH = 0.06f
        const val RING_GAP = 1.15f
        const val START_ANGLE = -90f
        const val FULL_TURN = 360f
        const val TEXT_WIDTH = 0.9f
        const val TEXT_TOP = 0.16f
        const val HINT_TOP = 0.88f
        const val LINE_GAP = 1.5f
        const val RAINBOW_MS_PER_DEGREE = 10f
        const val SATURATION = 0.7f
        val BLUE = Color.rgb(64, 156, 255)
        val AMBER = Color.rgb(255, 176, 32)
        val GREEN = Color.rgb(48, 209, 88)
        val RED = Color.rgb(255, 69, 58)
        val GREY = Color.rgb(120, 120, 128)
    }
}
