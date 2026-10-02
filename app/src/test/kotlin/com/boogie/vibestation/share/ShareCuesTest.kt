package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for [ShareCues]: which changes are worth a tap. */
class ShareCuesTest {
    private val playlist = SessionHarness.manifest(ShareKind.PLAYLIST, "a", "b", "c", name = "Road Trip")

    private fun transferring(done: Int) = ShareState.Transferring("Sam", playlist, true, done, 3, 0, 100)

    private fun finger(active: Boolean = true, armed: Boolean = false) =
        GestureState(active, 0f, 0f, GestureZone.YES, 0f, armed, blocked = false)

    /** Pairing is felt twice: the code appearing, then the connection opening. */
    @Test
    fun pairingCues() {
        val code = ShareState.Verifying("Sam", "4821", false)
        assertEquals(ShareCue.CODE, ShareCues.between(ShareState.Searching(), code))
        assertEquals(ShareCue.CODE, ShareCues.between(ShareState.Connecting("Sam"), code))
        assertNull(ShareCues.between(code, code.copy(confirmed = true)))
        assertEquals(ShareCue.CONNECTED, ShareCues.between(code.copy(confirmed = true), ShareState.Locked("Sam")))
    }

    /** An offer arriving is announced once, not again when the same offer is re-delivered. */
    @Test
    fun incomingOfferCue() {
        val offer = ShareState.IncomingOffer("Sam", playlist, listOf(0), 10)
        assertEquals(ShareCue.OFFER_IN, ShareCues.between(ShareState.Locked("Sam"), offer))
        assertNull(ShareCues.between(offer, offer))
    }

    /** A song finishing is felt, ordinary progress is not. */
    @Test
    fun fileDoneCue() {
        assertEquals(ShareCue.FILE_DONE, ShareCues.between(transferring(0), transferring(1)))
        assertNull(ShareCues.between(transferring(1), transferring(1)))
        assertNull(ShareCues.between(ShareState.Offering("Sam", playlist), transferring(0)))
    }

    /** A finished share is felt when its result first appears. */
    @Test
    fun doneCue() {
        val sent = ShareResult.Sent("Road Trip", 3, 0)
        assertEquals(ShareCue.DONE, ShareCues.between(transferring(3), ShareState.Locked("Sam", sent)))
        val got = ShareResult.Received("Road Trip", 3, 0, "Road Trip")
        val offer = ShareState.IncomingOffer("Sam", playlist, emptyList(), 0)
        assertEquals(ShareCue.DONE, ShareCues.between(offer, ShareState.Locked("Sam", got)))
    }

    /** The same result delivered again, as when the screen reopens, is not a new event. */
    @Test
    fun sameResultIsNotAnnouncedTwice() {
        val locked = ShareState.Locked("Sam", ShareResult.Sent("Road Trip", 3, 0))
        assertNull(ShareCues.between(locked, locked))
        assertNull(ShareCues.between(locked, locked.copy(peerName = "Sam")))
    }

    /** Two identical refusals in a row are two events, since each is a separate result. */
    @Test
    fun identicalResultsInARowAreSeparateEvents() {
        val first = ShareState.Locked("Sam", ShareResult.Refused(DeclineReason.BUSY, byPeer = true))
        val second = ShareState.Locked("Sam", ShareResult.Refused(DeclineReason.BUSY, byPeer = true))
        assertEquals(ShareCue.REFUSED, ShareCues.between(first, second))
    }

    /** A refusal from the other phone, or one forced by lack of space, is felt; declining yourself was just felt. */
    @Test
    fun refusalCue() {
        val offering = ShareState.Offering("Sam", playlist)
        fun refused(reason: DeclineReason, byPeer: Boolean) =
            ShareState.Locked("Sam", ShareResult.Refused(reason, byPeer))
        assertEquals(ShareCue.REFUSED, ShareCues.between(offering, refused(DeclineReason.USER, true)))
        assertEquals(ShareCue.REFUSED, ShareCues.between(offering, refused(DeclineReason.BUSY, true)))
        assertEquals(ShareCue.REFUSED, ShareCues.between(ShareState.Locked("Sam"), refused(DeclineReason.NO_SPACE, false)))
        assertNull(ShareCues.between(ShareState.IncomingOffer("Sam", playlist, listOf(0), 1), refused(DeclineReason.USER, false)))
    }

    /** Losing the connection or closing for a reason is felt; leaving on purpose is not. */
    @Test
    fun lostCue() {
        assertEquals(ShareCue.LOST, ShareCues.between(transferring(1), ShareState.Searching("Connection lost")))
        assertEquals(ShareCue.LOST, ShareCues.between(ShareState.Searching(), ShareState.Closed("Radio failed")))
        assertNull(ShareCues.between(ShareState.Searching(), ShareState.Closed()))
        assertNull(ShareCues.between(ShareState.Locked("Sam"), ShareState.Searching()))
        assertNull(ShareCues.between(ShareState.Searching(), ShareState.Searching("Versions differ")))
    }

    /** Putting a finger down, arming a zone, and backing out each have their own cue. */
    @Test
    fun fingerCues() {
        val idle = finger(active = false)
        assertEquals(ShareCue.PRESS, ShareCues.between(idle, finger()))
        assertNull(ShareCues.between(finger(), finger()))
        assertEquals(ShareCue.ARM, ShareCues.between(finger(), finger(armed = true)))
        assertNull(ShareCues.between(finger(armed = true), finger(armed = true)))
        assertEquals(ShareCue.DISARM, ShareCues.between(finger(armed = true), finger()))
    }

    /** Lifting the finger ends the drag silently: the commit has its own cue and a cancelled drag has none. */
    @Test
    fun liftingIsNotADisarm() {
        assertNull(ShareCues.between(finger(armed = true), finger(active = false)))
    }

    /** Committing maps to its own cue; letting go of nothing gives none. */
    @Test
    fun commitCues() {
        assertEquals(ShareCue.COMMIT_YES, ShareCues.committed(GestureOutcome.YES))
        assertEquals(ShareCue.COMMIT_NO, ShareCues.committed(GestureOutcome.NO))
        assertNull(ShareCues.committed(GestureOutcome.NONE))
    }
}
