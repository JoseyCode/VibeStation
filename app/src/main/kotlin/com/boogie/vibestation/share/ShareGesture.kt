package com.boogie.vibestation.share

import kotlin.math.abs
import kotlin.math.hypot

/** What lifting the finger meant. */
internal enum class GestureOutcome {
    /** Nothing was armed, or there was no active drag; the screen settles back. */
    NONE,

    /** Released in the upper half: send, accept, or confirm. */
    YES,

    /** Released in the lower half: close, decline, or reject. */
    NO
}

/** Which half of the screen the finger is in. The strip around the middle belongs to neither. */
internal enum class GestureZone { NONE, YES, NO }

/**
 * Snapshot of a drag for drawing and haptics.
 *
 * @property active   True while a finger is down and the gesture has not been invalidated.
 * @property x        Finger position in pixels; meaningless when not [active].
 * @property y        Finger position in pixels; meaningless when not [active].
 * @property zone     The half the finger is in, or NONE in the middle strip or when not [active].
 * @property progress How far into the zone the finger is, from -1 (bottom edge, "no") to 1 (top edge, "yes");
 *                    0 in the middle strip and in a zone that currently means nothing.
 * @property armed    True while releasing would commit.
 * @property blocked  True while the finger is in a zone that currently means nothing, so the screen can say so.
 */
internal data class GestureState(
    val active: Boolean,
    val x: Float,
    val y: Float,
    val zone: GestureZone,
    val progress: Float,
    val armed: Boolean,
    val blocked: Boolean
)

/**
 * The touch vocabulary of Share Mode as pure math: put a finger down anywhere and drag it anywhere. Above the
 * middle of the screen is yes, below is no, and a thin strip around the middle is neither. A zone arms once the
 * finger is clearly inside it and has moved more than the touch slop from where it went down, so a plain tap
 * never commits; lifting while armed commits. Once armed it stays armed until the finger backs out a little,
 * so a finger resting on the boundary does not flicker. Nothing here touches Android.
 *
 * @param heightPx Height of the touch area in pixels; the middle is half of it. Must be positive.
 * @param slopPx   How far the finger must travel from where it went down before anything can arm; not negative.
 */
@Suppress("TooManyFunctions") // one state machine; its private helpers are one-liners
internal class ShareGesture(private val heightPx: Float, private val slopPx: Float) {

    init {
        require(heightPx > 0f) { "heightPx must be positive" }
        require(slopPx >= 0f) { "slopPx must not be negative" }
    }

    /** Whether the upper half currently means something. The session changes this with its state. */
    var allowYes: Boolean = true

    /** Whether the lower half currently means something. The session changes this with its state. */
    var allowNo: Boolean = true

    private var active = false
    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f
    private var moved = false
    private var armedZone = GestureZone.NONE

    /** Current drag snapshot. */
    val state: GestureState
        get() {
            if (!active) return GestureState(false, x, y, GestureZone.NONE, 0f, armed = false, blocked = false)
            val offset = offsetOf(y)
            val zone = zoneOf(offset)
            val blocked = isBlocked(zone)
            val progress = if (blocked || zone == GestureZone.NONE) 0f else depth(offset) * (if (offset > 0f) 1f else -1f)
            val armed = zone != GestureZone.NONE && zone == armedZone && !blocked
            return GestureState(true, x, y, zone, progress, armed, blocked)
        }

    /**
     * Starts a drag. A gesture only ever begins here, so one that was invalidated cannot be resumed.
     *
     * @param x Horizontal touch position in pixels.
     * @param y Vertical touch position in pixels (screen coordinates grow downward).
     * @return The new state, which is never armed.
     */
    fun down(x: Float, y: Float): GestureState {
        active = true
        startX = x
        startY = y
        moved = false
        armedZone = GestureZone.NONE
        return follow(x, y)
    }

    /**
     * Moves the finger. Ignored when no drag is active.
     *
     * @param x Horizontal touch position in pixels.
     * @param y Vertical touch position in pixels.
     * @return The new state.
     */
    fun move(x: Float, y: Float): GestureState = if (active) follow(x, y) else state

    /**
     * Ends the drag and reports whether it committed.
     *
     * @return [GestureOutcome.YES] or [GestureOutcome.NO] if released while armed, else NONE.
     */
    fun up(): GestureOutcome {
        val outcome = if (!active) {
            GestureOutcome.NONE
        } else {
            when (state.takeIf { it.armed }?.zone) {
                GestureZone.YES -> GestureOutcome.YES
                GestureZone.NO -> GestureOutcome.NO
                else -> GestureOutcome.NONE
            }
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

    private fun follow(newX: Float, newY: Float): GestureState {
        x = newX
        y = newY
        if (!moved && hypot(newX - startX, newY - startY) > slopPx) moved = true
        val offset = offsetOf(newY)
        val zone = zoneOf(offset)
        val threshold = if (armedZone == zone) ARM_AT - HYSTERESIS else ARM_AT
        armedZone = if (canArm(zone) && depth(offset) >= threshold) zone else GestureZone.NONE
        return state
    }

    /** A zone can only arm once the finger has really travelled, and only if the zone means something right now. */
    private fun canArm(zone: GestureZone) = moved && zone != GestureZone.NONE && !isBlocked(zone)

    /** Where [y] is relative to the middle: 1 at the top edge, -1 at the bottom edge. */
    private fun offsetOf(y: Float) = ((heightPx / 2f - y) / (heightPx / 2f)).coerceIn(-1f, 1f)

    private fun zoneOf(offset: Float) = when {
        abs(offset) <= DEAD_BAND -> GestureZone.NONE
        offset > 0f -> GestureZone.YES
        else -> GestureZone.NO
    }

    private fun isBlocked(zone: GestureZone) =
        (zone == GestureZone.YES && !allowYes) || (zone == GestureZone.NO && !allowNo)

    /** How deep into its zone [offset] is: 0 at the edge of the middle strip, 1 at the edge of the screen. */
    private fun depth(offset: Float) = ((abs(offset) - DEAD_BAND) / (1f - DEAD_BAND)).coerceIn(0f, 1f)

    private fun reset() {
        active = false
        moved = false
        armedZone = GestureZone.NONE
    }

    /** Tuning for the zones. */
    companion object {
        /** Half-width of the middle strip, as a fraction of half the height. */
        const val DEAD_BAND = 0.08f

        /** Depth into a zone at which it arms. */
        const val ARM_AT = 0.25f

        /** How far back out of an armed zone the finger must come before it disarms. */
        const val HYSTERESIS = 0.06f
    }
}
