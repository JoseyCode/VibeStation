package com.boogie.vibestation.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.boogie.vibestation.share.GestureOutcome
import com.boogie.vibestation.share.GestureState
import com.boogie.vibestation.share.GestureZone
import com.boogie.vibestation.share.ShareCue
import com.boogie.vibestation.share.ShareCues
import com.boogie.vibestation.share.ShareGesture
import com.boogie.vibestation.share.ShareHaptics
import com.boogie.vibestation.share.ShareMotion
import com.boogie.vibestation.share.ShareScreenText
import com.boogie.vibestation.share.ShareState
import com.boogie.vibestation.share.ShareTint
import com.boogie.vibestation.share.ShareWaves
import com.boogie.vibestation.share.allowsNo
import com.boogie.vibestation.share.allowsYes
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The whole Share Mode screen: layered translucent waves across the middle of the screen, in the same style as
 * the player's visualizer, slowly drifting through every colour of the spectrum. Put a finger down anywhere and
 * a small dot follows it; the wave is pulled toward the dot like a string, and turns green or red as it goes into
 * a zone. Above the middle is yes (send, accept, confirm the code) and below is no (close, decline, reject);
 * letting go with the dot well inside a zone commits it. The waves change character with the state: loose and
 * pinging while searching, locking into one wave when the phones pair, and filling like a progress bar while
 * songs move. Taps, zones, state changes and finishes all have a haptic cue. It only draws and reports the
 * user's decision; what happens is up to whoever sets [onYes] and [onNo].
 */
@Suppress("TooManyFunctions", "LargeClass") // one view: touch, easing and every layer of drawing belong together
class ShareWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Words(val title: String, val detail: String)

    private class Ripple(val x: Float, val y: Float, val color: Int, val startMs: Long)

    private val density = resources.displayMetrics.density
    private val haptics = ShareHaptics(this)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var gesture = ShareGesture(1f, touchSlop)
    private var hasState = false
    private var text = ShareScreenText.of(ShareState.Idle)
    private var words = Words(text.title, text.detail)
    private var previousWords: Words? = null
    private var wordsFade = 1f

    private var targetMotion = ShareMotion.of(ShareState.Idle)
    private var motion = ShareMotion.REST
    private var tintColor = Color.WHITE
    private var clock = 0f
    private var phase = 0f
    private var pingClock = 0f
    private var burst = 0f
    private var lastFrameMs = 0L
    private var reduceMotion = false
    private var insetTop = 0
    private var insetBottom = 0

    private var touch = 0f
    private var zoneBlend = 0f
    private var armedAmount = 0f
    private var wasActive = false
    private var dotX = 0f
    private var dotY = 0f
    private var yesVisible = 0f
    private var noVisible = 0f
    private var lastYesLabel = ""
    private var lastNoLabel = ""
    private var coverAmount = 0f
    private var shownCover: Bitmap? = null
    private var codeAmount = 0f
    private var codeAge = 0f
    private var shownCode = ""
    private var actionIds = emptyList<Int>()
    private val ripples = ArrayList<Ripple>()

    private val hsv = FloatArray(HSV_PARTS)
    private val xs = FloatArray(BINS)
    private val centerY = FloatArray(BINS)
    private val pingHalf = FloatArray(BINS)
    private val top = FloatArray(BINS)
    private val bottom = FloatArray(BINS)
    private val digitWidths = FloatArray(MAX_CODE_DIGITS)
    private val codeChars = CharArray(MAX_CODE_DIGITS)
    private val wavePath = Path()
    private val linePath = Path()
    private val coverPath = Path()
    private val coverDest = RectF()
    private val coverSource = Rect()

    private val backdropPaint = Paint()
    private val washYesPaint = Paint()
    private val washNoPaint = Paint()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val titlePaint = textPaint(TITLE_SP, "sans-serif-medium")
    private val detailPaint = textPaint(DETAIL_SP, "sans-serif")
    private val labelPaint = textPaint(LABEL_SP, "sans-serif-medium").apply { letterSpacing = LABEL_SPACING }
    private val codePaint = textPaint(CODE_SP, "sans-serif-light")

    /** The track accent the colours start from; the waves and the glow behind them then drift through the whole spectrum. */
    var accent: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Cover art of what is being shared. It is shown above the words while an offer is out, incoming or moving;
     * set it to null when there is none.
     */
    var cover: Bitmap? = null
        set(value) {
            field = value
            if (value != null) shownCover = value
            invalidate()
        }

    /** What Share Mode is doing; assigning glides the waves to the new look and makes any drag that began earlier count for nothing. */
    internal var shareState: ShareState = ShareState.Idle
        set(value) {
            val old = field
            field = value
            onStateChanged(old, value)
        }

    /** Called when the user commits to yes by letting go with the dot in the upper half. */
    internal var onYes: (() -> Unit)? = null

    /** Called when the user commits to no by letting go with the dot in the lower half. */
    internal var onNo: (() -> Unit)? = null

    init {
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            insetTop = bars.top
            insetBottom = bars.bottom
            insets
        }
        updateAccessibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reduceMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        lastFrameMs = 0L
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gesture = ShareGesture(max(h, 1).toFloat(), touchSlop).also {
            it.allowYes = shareState.allowsYes
            it.allowNo = shareState.allowsNo
        }
        val glow = intArrayOf(withAlpha(Color.WHITE, GLOW_TOP_ALPHA), withAlpha(Color.WHITE, GLOW_MID_ALPHA), withAlpha(Color.WHITE, 0))
        backdropPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), glow, GLOW_STOPS, Shader.TileMode.CLAMP)
        val reach = h * WASH_REACH
        washYesPaint.shader = LinearGradient(0f, 0f, 0f, reach, GREEN, withAlpha(GREEN, 0), Shader.TileMode.CLAMP)
        washNoPaint.shader = LinearGradient(0f, h - reach, 0f, h.toFloat(), withAlpha(RED, 0), RED, Shader.TileMode.CLAMP)
        dotX = w / 2f
        dotY = h / 2f
        // Keep the system's edge swipes (back) from stealing a drag that starts near the side of the screen.
        ViewCompat.setSystemGestureExclusionRects(this, listOf(Rect(0, 0, w, h)))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val before = gesture.state
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                gesture.down(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> gesture.move(event.x, event.y)
            MotionEvent.ACTION_UP -> if (finish(before) != GestureOutcome.NONE) performClick()
            MotionEvent.ACTION_CANCEL -> gesture.cancel()
            else -> Unit
        }
        ShareCues.between(before, gesture.state)?.let(::onCue)
        invalidate()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** Lifts the finger: commits if a zone was armed. [last] is the drag as it was just before letting go. */
    private fun finish(last: GestureState): GestureOutcome {
        val outcome = gesture.up()
        ShareCues.committed(outcome)?.let(::onCue)
        when (outcome) {
            GestureOutcome.YES -> {
                ripples += Ripple(last.x, last.y, GREEN, SystemClock.uptimeMillis())
                onYes?.invoke()
            }
            GestureOutcome.NO -> {
                ripples += Ripple(last.x, last.y, RED, SystemClock.uptimeMillis())
                onNo?.invoke()
            }
            GestureOutcome.NONE -> Unit
        }
        invalidate()
        return outcome
    }

    private fun onStateChanged(old: ShareState, new: ShareState) {
        val classChanged = old::class != new::class
        gesture.allowYes = new.allowsYes
        gesture.allowNo = new.allowsNo
        if (classChanged) gesture.invalidate()
        targetMotion = ShareMotion.of(new)
        text = ShareScreenText.of(new)
        showWords(Words(text.title, text.detail), animate = classChanged || text.title != words.title)
        text.code?.let {
            if (it != shownCode) codeAge = 0f
            shownCode = it
        }
        text.yesLabel?.let { lastYesLabel = it }
        text.noLabel?.let { lastNoLabel = it }
        if (hasState) ShareCues.between(old, new)?.let(::onCue)
        hasState = true
        updateAccessibility()
        invalidate()
    }

    /** Swaps the words, sliding the old ones away and the new ones in, or just updating them when only the detail changed. */
    private fun showWords(next: Words, animate: Boolean) {
        if (animate && hasState) {
            previousWords = words
            wordsFade = 0f
        }
        words = next
    }

    private fun onCue(cue: ShareCue) {
        haptics.play(cue)
        when (cue) {
            ShareCue.CONNECTED, ShareCue.DONE -> burst = 1f
            ShareCue.OFFER_IN -> burst = OFFER_BURST
            ShareCue.FILE_DONE -> burst = max(burst, FILE_BURST)
            ShareCue.REFUSED, ShareCue.LOST -> burst = max(burst, SOFT_BURST)
            else -> Unit
        }
    }

    /** Gives screen readers the two answers as actions, since they cannot drag. */
    private fun updateAccessibility() {
        contentDescription = listOf(text.title, text.detail).filter { it.isNotEmpty() }.joinToString(". ")
        actionIds.forEach { ViewCompat.removeAccessibilityAction(this, it) }
        actionIds = listOfNotNull(
            text.yesLabel?.let { ViewCompat.addAccessibilityAction(this, it) { _, _ -> onYes?.invoke() != null } },
            text.noLabel?.let { ViewCompat.addAccessibilityAction(this, it) { _, _ -> onNo?.invoke() != null } }
        )
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameMs == 0L) 0f else min(now - lastFrameMs, MAX_FRAME_MS) / MILLIS
        lastFrameMs = now
        val finger = gesture.state
        advance(dt, finger)
        drawBackdrop(canvas)
        drawWaves(canvas)
        drawCover(canvas)
        drawWords(canvas)
        drawCode(canvas)
        drawZoneLabels(canvas)
        drawRipples(canvas, now)
        drawDot(canvas)
        postInvalidateOnAnimation()
    }

    /** Moves every animated value a little toward where it is heading. */
    private fun advance(dt: Float, finger: GestureState) {
        clock += dt
        val ease = if (reduceMotion) 1f else ShareMotion.smoothing(dt)
        motion = motion.easeToward(targetMotion, ease)
        tintColor = ColorUtils.blendARGB(tintColor, tintFor(targetMotion.tint), ease)
        if (!reduceMotion) {
            phase += dt * PHASE_RATE * motion.speed
            pingClock += dt * PING_RATE * motion.ping
        }
        burst = max(0f, burst - dt / BURST_SECONDS)
        wordsFade = min(1f, wordsFade + dt / WORDS_SECONDS)
        if (wordsFade >= 1f) previousWords = null

        touch = approach(touch, if (finger.active) 1f else 0f, TOUCH_RATE, dt)
        zoneBlend = approach(zoneBlend, finger.progress, ZONE_RATE, dt)
        armedAmount = approach(armedAmount, if (finger.armed) 1f else 0f, ARM_RATE, dt)
        followFinger(finger, dt)

        val yesTarget = labelTarget(text.yesLabel, finger, GestureZone.YES)
        val noTarget = labelTarget(text.noLabel, finger, GestureZone.NO)
        yesVisible = approach(yesVisible, yesTarget, LABEL_RATE, dt)
        noVisible = approach(noVisible, noTarget, LABEL_RATE, dt)

        val wantsCover = shownCover != null && cover != null && shareState.hasManifest
        coverAmount = approach(coverAmount, if (wantsCover) 1f else 0f, COVER_RATE, dt)
        if (!wantsCover && coverAmount < HIDDEN) shownCover = null

        codeAge = if (text.code != null) codeAge + dt else codeAge
        codeAmount = approach(codeAmount, if (text.code != null) 1f else 0f, COVER_RATE, dt)
    }

    /** The dot lands on a new touch at once, then glides after the finger; in a zone that means nothing it resists. */
    private fun followFinger(finger: GestureState, dt: Float) {
        if (finger.active) {
            val mid = height / 2f
            val targetY = if (finger.blocked) mid + (finger.y - mid) * RUBBER_BAND else finger.y
            if (!wasActive) {
                dotX = finger.x
                dotY = targetY
            } else {
                dotX = approach(dotX, finger.x, DOT_RATE, dt)
                dotY = approach(dotY, targetY, DOT_RATE, dt)
            }
        }
        wasActive = finger.active
    }

    private fun labelTarget(label: String?, finger: GestureState, zone: GestureZone): Float = when {
        label == null -> 0f
        finger.zone == zone && finger.armed -> 1f
        finger.active -> LABEL_TOUCHED
        else -> LABEL_RESTING
    }

    private fun drawBackdrop(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        backdropPaint.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
        if (zoneBlend > WASH_MIN) {
            washYesPaint.alpha = (zoneBlend * WASH_ALPHA * FULL).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height * WASH_REACH, washYesPaint)
        } else if (zoneBlend < -WASH_MIN) {
            washNoPaint.alpha = (-zoneBlend * WASH_ALPHA * FULL).toInt()
            canvas.drawRect(0f, height * (1f - WASH_REACH), width.toFloat(), height.toFloat(), washNoPaint)
        }
    }

    private fun drawWaves(canvas: Canvas) {
        val w = width.toFloat()
        val mid = height / 2f
        val fingerAcross = dotX / max(w, 1f)
        val pull = touch * PULL_STRENGTH
        val amplitude = currentAmplitude()
        val maxHalf = height * WAVE_HEIGHT
        val drift = DRIFT_REST + (if (motion.flow >= 0f) DRIFT_OUT else DRIFT_IN) * motion.flow
        for (i in 0 until BINS) {
            val across = i / (BINS - 1f)
            xs[i] = across * w
            centerY[i] = mid + (dotY - mid) * ShareWaves.pull(across, fingerAcross, PULL_WIDTH) * pull
            val pings = ShareWaves.ping(across, ShareWaves.pingTravel(pingClock, 0, PINGS)) +
                ShareWaves.ping(across, ShareWaves.pingTravel(pingClock, 1, PINGS))
            pingHalf[i] = pings * motion.ping * PING_HEIGHT * height
        }
        val color = ColorUtils.blendARGB(tintColor, if (zoneBlend >= 0f) GREEN else RED, min(1f, abs(zoneBlend)) * ZONE_TINT)
        wavePaint.color = color
        val layerAlpha = LAYER_ALPHA * FULL * motion.visibility
        for (layer in 0 until ShareWaves.LAYER_COUNT) {
            for (i in 0 until BINS) {
                val thickness = ShareWaves.thickness(layer, i / (BINS - 1f), phase * drift, motion.coherence)
                val half = thickness * amplitude * maxHalf + pingHalf[i] + MIN_HALF_DP * density
                top[i] = centerY[i] - half
                bottom[i] = centerY[i] + half
            }
            wavePath.reset()
            SplinePath.add(wavePath, xs, top, BINS)
            SplinePath.add(wavePath, xs, bottom, BINS, reverse = true, move = false)
            wavePath.close()
            drawLayer(canvas, layerAlpha)
        }
        drawCenterLine(canvas, color, mid, maxHalf * amplitude)
    }

    /** Draws the layer in [wavePath]; during a transfer the finished part is bright and the rest dim. */
    private fun drawLayer(canvas: Canvas, alpha: Float) {
        val fill = motion.fill
        if (fill == null) {
            wavePaint.alpha = alpha.toInt()
            canvas.drawPath(wavePath, wavePaint)
            return
        }
        val split = fill * width
        canvas.save()
        canvas.clipRect(0f, 0f, split, height.toFloat())
        wavePaint.alpha = min(FULL, alpha * FILL_BRIGHT).toInt()
        canvas.drawPath(wavePath, wavePaint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(split, 0f, width.toFloat(), height.toFloat())
        wavePaint.alpha = (alpha * FILL_DIM).toInt()
        canvas.drawPath(wavePath, wavePaint)
        canvas.restore()
    }

    private fun drawCenterLine(canvas: Canvas, color: Int, mid: Float, reach: Float) {
        linePath.reset()
        SplinePath.add(linePath, xs, centerY, BINS)
        val bright = ColorUtils.blendARGB(color, Color.WHITE, LINE_WHITENING)
        linePaint.color = bright
        linePaint.strokeWidth = GLOW_STROKE_DP * density
        linePaint.alpha = (GLOW_LINE_ALPHA * FULL * motion.visibility).toInt()
        canvas.drawPath(linePath, linePaint)
        linePaint.strokeWidth = LINE_STROKE_DP * density
        linePaint.alpha = (LINE_ALPHA * FULL * motion.visibility).toInt()
        canvas.drawPath(linePath, linePaint)
        val fill = motion.fill ?: return
        val playhead = fill * width
        canvas.drawLine(playhead, mid - reach * PLAYHEAD_REACH, playhead, mid + reach * PLAYHEAD_REACH, linePaint)
    }

    private fun currentAmplitude(): Float {
        val breathing = 1f + motion.breathing * BREATH_DEPTH * sin(clock * BREATH_RATE)
        val total = motion.amplitude * breathing * (1f + TOUCH_BOOST * touch) + burst * BURST_BOOST + armedAmount * ARM_BOOST
        return min(MAX_AMPLITUDE, total)
    }

    private fun drawCover(canvas: Canvas) {
        val bitmap = shownCover ?: return
        if (coverAmount < HIDDEN) return
        val side = coverSide()
        val scale = COVER_FROM_SCALE + (1f - COVER_FROM_SCALE) * coverAmount
        val half = side * scale / 2f
        val cx = width / 2f
        val cy = coverTop() + side / 2f
        coverDest.set(cx - half, cy - half, cx + half, cy + half)
        val radius = COVER_RADIUS_DP * density
        val drop = SHADOW_DROP_DP * density
        for (layer in SHADOW_LAYERS downTo 1) {
            val grow = layer * SHADOW_STEP_DP * density
            shadowPaint.alpha = (SHADOW_ALPHA * FULL * coverAmount / layer).toInt()
            canvas.drawRoundRect(
                coverDest.left - grow, coverDest.top - grow + drop, coverDest.right + grow, coverDest.bottom + grow + drop,
                radius + grow, radius + grow, shadowPaint
            )
        }
        val shorter = min(bitmap.width, bitmap.height)
        val left = (bitmap.width - shorter) / 2
        val topEdge = (bitmap.height - shorter) / 2
        coverSource.set(left, topEdge, left + shorter, topEdge + shorter)
        coverPath.reset()
        coverPath.addRoundRect(coverDest, radius, radius, Path.Direction.CW)
        coverPaint.alpha = (coverAmount * FULL).toInt()
        canvas.save()
        canvas.clipPath(coverPath)
        canvas.drawBitmap(bitmap, coverSource, coverDest, coverPaint)
        canvas.restore()
    }

    private fun coverSide() = min(width * COVER_WIDTH, height * COVER_HEIGHT)

    private fun coverTop() = insetTop + height * COVER_TOP

    /** Where the title sits: lower down to make room for the cover when there is one. */
    private fun titleBaseline(): Float {
        val withoutCover = insetTop + height * TITLE_TOP
        val withCover = coverTop() + coverSide() + TITLE_BELOW_COVER_DP * density
        return withoutCover + (withCover - withoutCover) * coverAmount
    }

    private fun drawWords(canvas: Canvas) {
        val y = titleBaseline()
        val slide = SLIDE_DP * density
        val fade = ShareWaves.smoothstep(wordsFade)
        previousWords?.let { drawWordPair(canvas, it, y - slide * fade, 1f - fade) }
        drawWordPair(canvas, words, y + slide * (1f - fade), fade)
    }

    private fun drawWordPair(canvas: Canvas, pair: Words, y: Float, alpha: Float) {
        val cx = width / 2f
        val room = width * TEXT_WIDTH
        titlePaint.alpha = (alpha * motion.visibility.coerceAtLeast(MIN_TEXT_VISIBILITY) * FULL).toInt()
        canvas.drawText(fit(pair.title, titlePaint, room), cx, y, titlePaint)
        if (pair.detail.isNotEmpty()) {
            detailPaint.alpha = (alpha * DETAIL_ALPHA * motion.visibility.coerceAtLeast(MIN_TEXT_VISIBILITY) * FULL).toInt()
            canvas.drawText(fit(pair.detail, detailPaint, room), cx, y + titlePaint.textSize * DETAIL_GAP, detailPaint)
        }
    }

    /** Draws the verification code large, its digits popping in one after another. */
    private fun drawCode(canvas: Canvas) {
        if (codeAmount < HIDDEN || shownCode.isEmpty()) return
        val count = min(shownCode.length, MAX_CODE_DIGITS)
        shownCode.toCharArray(codeChars, 0, 0, count)
        val gap = codePaint.textSize * CODE_GAP
        var total = gap * (count - 1)
        for (i in 0 until count) {
            digitWidths[i] = codePaint.measureText(codeChars, i, 1)
            total += digitWidths[i]
        }
        val baseline = height / 2f + height * CODE_OFFSET
        var x = (width - total) / 2f
        for (i in 0 until count) {
            val t = (codeAge - i * DIGIT_STAGGER) / DIGIT_POP
            val scale = ShareWaves.overshoot(t)
            val centre = x + digitWidths[i] / 2f
            codePaint.alpha = (t.coerceIn(0f, 1f) * codeAmount * FULL).toInt()
            canvas.save()
            canvas.scale(scale, scale, centre, baseline - codePaint.textSize * CODE_PIVOT)
            canvas.drawText(codeChars, i, 1, centre, baseline, codePaint)
            canvas.restore()
            x += digitWidths[i] + gap
        }
    }

    private fun drawZoneLabels(canvas: Canvas) {
        drawZoneLabel(canvas, lastYesLabel, yesVisible, if (zoneBlend > 0f) armedAmount else 0f, GREEN, atTop = true)
        drawZoneLabel(canvas, lastNoLabel, noVisible, if (zoneBlend < 0f) armedAmount else 0f, RED, atTop = false)
    }

    /** A label and a chevron at the top or bottom edge; it swells and takes the zone's colour once armed. */
    private fun drawZoneLabel(canvas: Canvas, label: String, visible: Float, armed: Float, color: Int, atTop: Boolean) {
        if (visible < HIDDEN || label.isEmpty()) return
        val cx = width / 2f
        val baseline = if (atTop) insetTop + LABEL_EDGE_DP * density else height - insetBottom - LABEL_EDGE_DP * density
        val direction = if (atTop) -1f else 1f
        val tone = ColorUtils.blendARGB(Color.WHITE, color, armed * ARMED_TONE)
        val scale = 1f + ARMED_SWELL * armed
        val alpha = (visible * FULL).toInt()
        labelPaint.color = tone
        labelPaint.alpha = alpha
        linePaint.color = tone
        linePaint.alpha = alpha
        linePaint.strokeWidth = CHEVRON_STROKE_DP * density
        val bob = sin(clock * BOB_RATE) * BOB_DP * density * (1f - touch) * direction
        val chevronY = baseline + direction * (CHEVRON_GAP_DP * density) + (if (atTop) -labelPaint.textSize else 0f) + bob
        val reach = CHEVRON_SIZE_DP * density
        canvas.save()
        canvas.scale(scale, scale, cx, baseline)
        canvas.drawText(fit(label, labelPaint, width * LABEL_WIDTH), cx, baseline, labelPaint)
        canvas.drawLine(cx - reach, chevronY - direction * reach / 2f, cx, chevronY + direction * reach / 2f, linePaint)
        canvas.drawLine(cx, chevronY + direction * reach / 2f, cx + reach, chevronY - direction * reach / 2f, linePaint)
        canvas.restore()
    }

    private fun drawRipples(canvas: Canvas, now: Long) {
        for (i in ripples.indices.reversed()) {
            val ripple = ripples[i]
            val t = (now - ripple.startMs) / RIPPLE_MS
            if (t >= 1f) {
                ripples.removeAt(i)
                continue
            }
            val eased = 1f - (1f - t) * (1f - t)
            linePaint.color = ripple.color
            linePaint.alpha = ((1f - t) * RIPPLE_ALPHA * FULL).toInt()
            linePaint.strokeWidth = (RIPPLE_STROKE_DP * density) * (1f - t) + 1f
            val radius = DOT_BASE_DP * density + max(width, height) * RIPPLE_REACH * eased
            canvas.drawCircle(ripple.x, ripple.y, radius, linePaint)
        }
    }

    private fun drawDot(canvas: Canvas) {
        val alpha = touch
        if (alpha < HIDDEN) return
        val radius = (DOT_BASE_DP + DOT_ARMED_GROWTH_DP * armedAmount) * density
        val tone = ColorUtils.blendARGB(Color.WHITE, if (zoneBlend >= 0f) GREEN else RED, armedAmount * ARMED_TONE)
        dotPaint.style = Paint.Style.FILL
        dotPaint.color = tone
        for ((scale, strength) in DOT_GLOW) {
            dotPaint.alpha = (strength * alpha * FULL).toInt()
            canvas.drawCircle(dotX, dotY, radius * scale, dotPaint)
        }
        dotPaint.alpha = (alpha * FULL).toInt()
        canvas.drawCircle(dotX, dotY, radius, dotPaint)
        if (armedAmount > HIDDEN) {
            dotPaint.style = Paint.Style.STROKE
            dotPaint.strokeWidth = DOT_RING_DP * density
            dotPaint.alpha = (armedAmount * alpha * FULL).toInt()
            canvas.drawCircle(dotX, dotY, radius * DOT_RING_SCALE * (1f + DOT_RING_PULSE * sin(clock * RING_RATE)), dotPaint)
        }
    }

    private fun tintFor(tint: ShareTint) = when (tint) {
        ShareTint.ACCENT -> ambientColor()
        ShareTint.ATTENTION -> AMBER
        ShareTint.MUTED -> GREY
    }

    /** A pastel colour that slowly goes round the colour wheel, beginning at the accent's hue; still when motion is reduced. */
    private fun ambientColor(): Int {
        Color.colorToHSV(accent, hsv)
        if (!reduceMotion) hsv[0] = ShareWaves.hue(hsv[0], clock)
        hsv[1] = AMBIENT_SATURATION
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    private fun fit(text: String, paint: TextPaint, room: Float): String =
        TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString()

    private fun textPaint(sp: Float, family: String) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        typeface = Typeface.create(family, Typeface.NORMAL)
    }

    /** Moves [current] toward [target] at [rate] per second, the same distance per second at any frame rate. */
    private fun approach(current: Float, target: Float, rate: Float, dt: Float): Float =
        if (reduceMotion) target else current + (target - current) * (1f - exp(-rate * dt))

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private val ShareState.hasManifest: Boolean
        get() = this is ShareState.IncomingOffer || this is ShareState.Offering || this is ShareState.Transferring

    private companion object {
        const val BINS = 48
        const val HSV_PARTS = 3
        const val AMBIENT_SATURATION = 0.6f
        const val PINGS = 2
        const val MAX_CODE_DIGITS = 8
        const val MILLIS = 1000f
        const val MAX_FRAME_MS = 50L
        const val FULL = 255f
        const val HIDDEN = 0.01f

        const val TITLE_SP = 24f
        const val DETAIL_SP = 15f
        const val LABEL_SP = 13f
        const val CODE_SP = 64f
        const val LABEL_SPACING = 0.08f
        const val TEXT_WIDTH = 0.86f
        const val LABEL_WIDTH = 0.7f
        const val DETAIL_ALPHA = 0.7f
        const val DETAIL_GAP = 1.5f
        const val MIN_TEXT_VISIBILITY = 0.55f
        const val TITLE_TOP = 0.24f
        const val TITLE_BELOW_COVER_DP = 40f
        const val SLIDE_DP = 8f
        const val WORDS_SECONDS = 0.28f

        const val COVER_WIDTH = 0.36f
        const val COVER_HEIGHT = 0.17f
        const val COVER_TOP = 0.11f
        const val COVER_RADIUS_DP = 20f
        const val COVER_FROM_SCALE = 0.88f
        const val COVER_RATE = 8f
        const val SHADOW_LAYERS = 3
        const val SHADOW_STEP_DP = 6f
        const val SHADOW_DROP_DP = 8f
        const val SHADOW_ALPHA = 0.16f

        const val CODE_OFFSET = 0.2f
        const val CODE_GAP = 0.22f
        const val CODE_PIVOT = 0.35f
        const val DIGIT_STAGGER = 0.09f
        const val DIGIT_POP = 0.4f

        const val WAVE_HEIGHT = 0.13f
        const val LAYER_ALPHA = 0.16f
        const val FILL_BRIGHT = 1.5f
        const val FILL_DIM = 0.35f
        const val PLAYHEAD_REACH = 0.7f
        const val MIN_HALF_DP = 1.2f
        const val LINE_STROKE_DP = 1.6f
        const val GLOW_STROKE_DP = 7f
        const val LINE_ALPHA = 0.85f
        const val GLOW_LINE_ALPHA = 0.14f
        const val LINE_WHITENING = 0.55f
        const val PHASE_RATE = 2.2f
        const val PING_RATE = 0.5f
        const val PING_HEIGHT = 0.06f
        const val DRIFT_REST = 0.35f
        const val DRIFT_OUT = 0.65f
        const val DRIFT_IN = 1.35f
        const val BREATH_DEPTH = 0.25f
        const val BREATH_RATE = 1.6f
        const val TOUCH_BOOST = 0.35f
        const val ARM_BOOST = 0.12f
        const val BURST_BOOST = 0.5f
        const val BURST_SECONDS = 0.9f
        const val OFFER_BURST = 0.6f
        const val FILE_BURST = 0.35f
        const val SOFT_BURST = 0.3f
        const val MAX_AMPLITUDE = 1.1f
        const val PULL_STRENGTH = 0.85f
        const val PULL_WIDTH = 0.22f
        const val ZONE_TINT = 0.8f
        const val WASH_ALPHA = 0.28f
        const val WASH_REACH = 0.45f
        const val WASH_MIN = 0.01f
        const val GLOW_TOP_ALPHA = 96
        const val GLOW_MID_ALPHA = 36

        const val TOUCH_RATE = 14f
        const val ZONE_RATE = 10f
        const val ARM_RATE = 16f
        const val DOT_RATE = 26f
        const val LABEL_RATE = 9f
        const val LABEL_TOUCHED = 0.75f
        const val LABEL_RESTING = 0.4f
        const val RUBBER_BAND = 0.3f

        const val DOT_BASE_DP = 9f
        const val DOT_ARMED_GROWTH_DP = 6f
        const val DOT_RING_DP = 2f
        const val DOT_RING_SCALE = 1.9f
        const val DOT_RING_PULSE = 0.08f
        const val RING_RATE = 7f
        const val ARMED_TONE = 0.6f
        const val ARMED_SWELL = 0.16f
        const val LABEL_EDGE_DP = 64f
        const val CHEVRON_GAP_DP = 22f
        const val CHEVRON_SIZE_DP = 8f
        const val CHEVRON_STROKE_DP = 2f
        const val BOB_RATE = 2.4f
        const val BOB_DP = 2.5f

        const val RIPPLE_MS = 650f
        const val RIPPLE_ALPHA = 0.6f
        const val RIPPLE_STROKE_DP = 3f
        const val RIPPLE_REACH = 0.8f

        val GLOW_STOPS = floatArrayOf(0f, 0.3f, 0.65f)
        val DOT_GLOW = listOf(3f to 0.1f, 2f to 0.18f, 1.4f to 0.32f)
        val GREEN = Color.rgb(48, 209, 88)
        val RED = Color.rgb(255, 69, 58)
        val AMBER = Color.rgb(255, 176, 32)
        val GREY = Color.rgb(120, 120, 128)
    }
}
