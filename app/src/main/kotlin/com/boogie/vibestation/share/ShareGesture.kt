package com.boogie.vibestation.share

import kotlin.math.abs

/** What lifting the finger meant. */
internal enum class GestureOutcome {
    /** The drag did not reach the threshold, or there was no active drag; the circle springs back. */
    NONE,

    /** Dragged up far enough: send, accept, or confirm. */
    YES,

    /** Dragged down far enough: close, decline, or reject. */
    NO
}

/**
 * Snapshot of a drag for drawing and haptics.
 *
 * @property active   True while a finger is down and the gesture has not been invalidated.
 * @property progress Drag distance as a fraction of the full travel, from -1 (fully down, "no") to 1
 *                    (fully up, "yes"). Always 0 in a direction that is not currently allowed.
 * @property armed    True once the drag is past the commit threshold, so releasing now will commit.
 */
internal data class GestureState(val active: Boolean, val progress: Float, val armed: Boolean)

/**
 * The hold-and-drag vocabulary of Share Mode as pure math: hold the circle, drag up for yes, drag down
 * for no, and sideways does nothing (only the vertical position is ever given to this class). The
 * custom view feeds it touch positions and draws [state]; nothing here touches Android.
 *
 * @param travelPx       Vertical drag distance that counts as a full swipe; must be positive.
 * @param commitFraction Fraction of [travelPx] at which a release commits, in (0, 1].
 */
internal class ShareGesture(private val travelPx: Float, private val commitFraction: Float = DEFAULT_COMMIT) {

    init {
        require(travelPx > 0f) { "travelPx must be positive" }
        require(commitFraction > 0f && commitFraction <= 1f) { "commitFraction must be in (0, 1]" }
    }

    /** Whether dragging up currently means something. The session changes this with its state. */
    var allowYes: Boolean = true

    /** Whether dragging down currently means something. The session changes this with its state. */
    var allowNo: Boolean = true

    private var active = false
    private var startY = 0f
    private var progress = 0f

    /** Current drag snapshot. */
    val state: GestureState
        get() = GestureState(active, progress, active && abs(progress) >= commitFraction)

    /**
     * Starts a drag. A gesture only ever begins here, so one that was invalidated cannot be resumed.
     *
     * @param y Vertical touch position in pixels (screen coordinates grow downward).
     * @return The new state, at zero progress.
     */
    fun down(y: Float): GestureState {
        active = true
        startY = y
        progress = 0f
        return state
    }

    /**
     * Updates the drag. Ignored when no drag is active.
     *
     * @param y Vertical touch position in pixels.
     * @return The new state; progress is clamped to [-1, 1] and held at 0 in a disallowed direction.
     */
    fun move(y: Float): GestureState {
        if (!active) return state
        val raw = ((startY - y) / travelPx).coerceIn(-1f, 1f)
        progress = when {
            raw > 0f && !allowYes -> 0f
            raw < 0f && !allowNo -> 0f
            else -> raw
        }
        return state
    }

    /**
     * Ends the drag and reports whether it committed.
     *
     * @return [GestureOutcome.YES] or [GestureOutcome.NO] if released past the threshold, else NONE.
     */
    fun up(): GestureOutcome {
        val outcome = when {
            !active -> GestureOutcome.NONE
            progress >= commitFraction -> GestureOutcome.YES
            progress <= -commitFraction -> GestureOutcome.NO
            else -> GestureOutcome.NONE
        }
        reset()
        return outcome
    }

    /** Abandons the drag (the system cancelled the touch); nothing commits. */
    fun cancel() = reset()

    /**
     * Kills a drag that is in progress because the screen changed underneath it, for example an offer
     * arrived. Further moves and the eventual release are ignored; the user must lift and touch again.
     */
    fun invalidate() = reset()

    private fun reset() {
        active = false
        progress = 0f
    }

    private companion object {
        const val DEFAULT_COMMIT = 0.6f
    }
}
