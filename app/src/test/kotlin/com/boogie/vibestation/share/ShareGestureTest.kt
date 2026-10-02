package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the [ShareGesture] zones. The area is 1000 px tall, so the middle is y=500 and screen y grows
 * downward. The middle strip is +-40 px; a zone arms about 155 px from the middle (y<=340 or y>=660 are safely
 * past it) and stays armed until the finger backs out to about 127 px from the middle.
 */
class ShareGestureTest {

    private val gesture = ShareGesture(heightPx = 1000f, slopPx = 10f)

    /** The finger is in the upper half, the lower half, or the middle strip, wherever it is sideways. */
    @Test
    fun zonesFollowTheHalves() {
        gesture.down(100f, 500f)
        assertEquals(GestureZone.YES, gesture.move(100f, 300f).zone)
        assertEquals(GestureZone.NO, gesture.move(100f, 700f).zone)
        assertEquals(GestureZone.NONE, gesture.move(100f, 520f).zone)
        assertEquals(GestureZone.NONE, gesture.move(100f, 480f).zone)
        assertEquals(GestureZone.YES, gesture.move(900f, 300f).zone)
    }

    /** Progress is how deep into the zone the finger is, positive up and negative down, clamped at the edges. */
    @Test
    fun progressIsDepthIntoTheZone() {
        gesture.down(100f, 500f)
        assertEquals(0f, gesture.move(100f, 500f).progress)
        assertEquals(0f, gesture.move(100f, 460f).progress, 1e-6f)
        assertEquals(1f, gesture.move(100f, 0f).progress)
        assertEquals(-1f, gesture.move(100f, 1000f).progress)
        assertEquals(1f, gesture.move(100f, -300f).progress)
        assertEquals(0.5f, gesture.move(100f, 500f - 40f - 0.5f * 460f).progress, 1e-5f)
    }

    /** Position and activity are reported for drawing the dot. */
    @Test
    fun stateReportsTheFinger() {
        val down = gesture.down(120f, 800f)
        assertTrue(down.active)
        assertEquals(120f, down.x)
        assertEquals(800f, down.y)
        val moved = gesture.move(300f, 200f)
        assertEquals(300f to 200f, moved.x to moved.y)
    }

    /** A tap that stays put never arms, even high in the yes half, and releasing it commits nothing. */
    @Test
    fun tapNeverCommits() {
        assertFalse(gesture.down(100f, 100f).armed)
        assertFalse(gesture.move(104f, 103f).armed)
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.down(100f, 900f)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** Pressing in a zone and then travelling past the slop arms it at once, since it is already deep enough. */
    @Test
    fun pressInAZoneThenMoveArms() {
        gesture.down(100f, 200f)
        assertFalse(gesture.move(100f, 205f).armed)
        assertTrue(gesture.move(100f, 215f).armed)
        assertEquals(GestureOutcome.YES, gesture.up())
    }

    /** Dragging from the middle up into the yes half and letting go says yes. */
    @Test
    fun releaseInTheUpperHalfIsYes() {
        gesture.down(500f, 500f)
        assertFalse(gesture.move(500f, 400f).armed)
        assertTrue(gesture.move(500f, 340f).armed)
        assertEquals(GestureOutcome.YES, gesture.up())
    }

    /** Dragging down into the no half and letting go says no. */
    @Test
    fun releaseInTheLowerHalfIsNo() {
        gesture.down(500f, 500f)
        assertFalse(gesture.move(500f, 600f).armed)
        assertTrue(gesture.move(500f, 660f).armed)
        assertEquals(GestureOutcome.NO, gesture.up())
    }

    /** Moving the finger sideways while it is in a zone changes nothing about being armed. */
    @Test
    fun sidewaysMovementKeepsItArmed() {
        gesture.down(100f, 500f)
        gesture.move(100f, 300f)
        assertTrue(gesture.move(900f, 300f).armed)
        assertTrue(gesture.move(500f, 250f).armed)
        assertEquals(GestureOutcome.YES, gesture.up())
    }

    /** Once armed, backing out a little does not disarm; backing out well past the threshold does. */
    @Test
    fun armingHasHysteresis() {
        gesture.down(100f, 500f)
        assertFalse(gesture.move(100f, 360f).armed)
        assertTrue(gesture.move(100f, 340f).armed)
        assertTrue(gesture.move(100f, 360f).armed)
        assertFalse(gesture.move(100f, 380f).armed)
        assertFalse(gesture.move(100f, 360f).armed)
    }

    /** Taking the finger back to the middle cancels the choice, and so does crossing to the other half. */
    @Test
    fun draggingBackCancelsCommit() {
        gesture.down(100f, 500f)
        gesture.move(100f, 300f)
        gesture.move(100f, 500f)
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.down(100f, 500f)
        gesture.move(100f, 300f)
        assertFalse(gesture.move(100f, 560f).armed)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** A zone that means nothing right now follows the finger but never arms or reports progress. */
    @Test
    fun blockedZoneDoesNothing() {
        gesture.allowYes = false
        gesture.down(100f, 500f)
        val up = gesture.move(100f, 200f)
        assertEquals(GestureZone.YES, up.zone)
        assertTrue(up.blocked)
        assertFalse(up.armed)
        assertEquals(0f, up.progress)
        assertEquals(200f, up.y)
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.allowYes = true
        gesture.allowNo = false
        gesture.down(100f, 500f)
        assertTrue(gesture.move(100f, 800f).blocked)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** The middle strip is never blocked, whatever is allowed. */
    @Test
    fun middleStripIsNeverBlocked() {
        gesture.allowYes = false
        gesture.allowNo = false
        assertFalse(gesture.down(100f, 500f).blocked)
    }

    /** A zone that stops meaning anything while the finger is in it disarms. */
    @Test
    fun zoneBlockedMidDragDisarms() {
        gesture.down(100f, 500f)
        assertTrue(gesture.move(100f, 200f).armed)
        gesture.allowYes = false
        assertFalse(gesture.state.armed)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** Without a finger down, movement and release do nothing. */
    @Test
    fun inactiveGestureIgnoresInput() {
        assertFalse(gesture.state.active)
        assertFalse(gesture.move(100f, 100f).active)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** An invalidated drag cannot come back to life: only a new touch down starts one. */
    @Test
    fun invalidatedGestureNeedsFreshTouch() {
        gesture.down(100f, 500f)
        gesture.move(100f, 200f)
        gesture.invalidate()

        assertFalse(gesture.state.active)
        assertFalse(gesture.move(100f, 100f).armed)
        assertEquals(GestureOutcome.NONE, gesture.up())

        gesture.down(100f, 500f)
        gesture.move(100f, 200f)
        assertEquals(GestureOutcome.YES, gesture.up())
    }

    /** Cancel ends the drag without committing. */
    @Test
    fun cancelClearsWithoutCommit() {
        gesture.down(100f, 500f)
        gesture.move(100f, 200f)
        gesture.cancel()
        assertFalse(gesture.state.active)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** Each drag starts clean: the previous one's armed zone and travel do not carry over. */
    @Test
    fun eachDragStartsFromItsOwnTouchDown() {
        gesture.down(100f, 500f)
        gesture.move(100f, 200f)
        gesture.up()

        assertFalse(gesture.down(100f, 200f).armed)
        assertEquals(GestureOutcome.NONE, gesture.up())
    }

    /** A zero slop arms as soon as the finger is deep enough. */
    @Test
    fun zeroSlopIsAllowed() {
        val sensitive = ShareGesture(1000f, 0f)
        sensitive.down(100f, 300f)
        assertTrue(sensitive.move(101f, 300f).armed)
    }

    /** Impossible configurations are refused. */
    @Test
    fun invalidConfigurationRejected() {
        assertFailsWith<IllegalArgumentException> { ShareGesture(0f, 10f) }
        assertFailsWith<IllegalArgumentException> { ShareGesture(-5f, 10f) }
        assertFailsWith<IllegalArgumentException> { ShareGesture(1000f, -1f) }
    }
}
