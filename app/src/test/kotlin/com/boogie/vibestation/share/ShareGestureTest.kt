package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for the [ShareGesture] drag math. Screen y grows downward, so dragging up means smaller y. */
class ShareGestureTest {

    private val gesture = ShareGesture(travelPx = 100f, commitFraction = 0.5f)

    /** Dragging up gives positive progress and dragging down negative, scaled by the travel. */
    @Test
    fun progressFollowsVerticalDrag() {
        gesture.down(500f)
        assertEquals(0.25f, gesture.move(475f).progress)
        assertEquals(-0.3f, gesture.move(530f).progress, 1e-6f)
    }

    /** Progress never leaves [-1, 1] however far the finger goes. */
    @Test
    fun progressIsClamped() {
        gesture.down(500f)
        assertEquals(1f, gesture.move(0f).progress)
        assertEquals(-1f, gesture.move(5000f).progress)
    }

    /** Armed flips exactly at the commit fraction, in either direction. */
    @Test
    fun armedAtThreshold() {
        gesture.down(500f)
        assertFalse(gesture.move(451f).armed)
        assertTrue(gesture.move(450f).armed)
        assertFalse(gesture.move(549f).armed)
        assertTrue(gesture.move(550f).armed)
    }

    /** Releasing past the threshold commits YES up and NO down; short of it springs back. */
    @Test
    fun releaseOutcomes() {
        gesture.down(500f)
        gesture.move(450f)
        assertEquals(GestureOutcome.YES, gesture.up())

        gesture.down(500f)
        gesture.move(550f)
        assertEquals(GestureOutcome.NO, gesture.up())

        gesture.down(500f)
        gesture.move(460f)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** A drag that went past the threshold and came back before release does not commit. */
    @Test
    fun draggingBackCancelsCommit() {
        gesture.down(500f)
        gesture.move(400f)
        gesture.move(500f)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** A disallowed direction stays at zero and can never commit. */
    @Test
    fun disallowedDirectionDoesNothing() {
        gesture.allowYes = false
        gesture.down(500f)
        assertEquals(0f, gesture.move(300f).progress)
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.allowYes = true
        gesture.allowNo = false
        gesture.down(500f)
        assertEquals(0f, gesture.move(700f).progress)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** Moves without a touch-down, and a release without one, are ignored. */
    @Test
    fun inactiveGestureIgnoresInput() {
        assertEquals(GestureState(false, 0f, false), gesture.move(0f))
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** An invalidated drag (an offer arrived mid-gesture) ignores its own release; a fresh touch works. */
    @Test
    fun invalidatedGestureNeedsFreshTouch() {
        gesture.down(500f)
        gesture.move(400f)
        gesture.invalidate()

        assertEquals(GestureState(false, 0f, false), gesture.move(300f))
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.down(500f)
        gesture.move(400f)
        assertEquals(GestureOutcome.YES, gesture.up())
    }

    /** A system cancel clears the drag without committing. */
    @Test
    fun cancelClearsWithoutCommit() {
        gesture.down(500f)
        gesture.move(400f)
        gesture.cancel()
        assertEquals(GestureOutcome.NONE, gesture.up())
        assertFalse(gesture.state.active)
    }

    /** Each drag is measured from where its own touch began. */
    @Test
    fun eachDragStartsFromItsOwnTouchDown() {
        gesture.down(500f)
        gesture.move(400f)
        gesture.up()
        assertEquals(0f, gesture.down(100f).progress)
        assertEquals(0.1f, gesture.move(90f).progress, 1e-6f)
    }

    /** Nonsense configuration is refused up front. */
    @Test
    fun invalidConfigurationRejected() {
        assertFailsWith<IllegalArgumentException> { ShareGesture(0f) }
        assertFailsWith<IllegalArgumentException> { ShareGesture(10f, 0f) }
        assertFailsWith<IllegalArgumentException> { ShareGesture(10f, 1.5f) }
    }
}
