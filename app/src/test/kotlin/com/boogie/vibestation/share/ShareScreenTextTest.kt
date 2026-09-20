package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareScreenTextTest {
    private val playlist = SessionHarness.manifest(ShareKind.PLAYLIST, "a", "b", "c", name = "Road Trip")

    @Test
    fun searchingShowsNoticeWhenThereIsOne() {
        assertEquals("Looking for a phone", ShareScreenText.of(ShareState.Searching()).title)
        assertEquals("Open Share Mode on the other phone", ShareScreenText.of(ShareState.Searching()).detail)
        assertEquals("Versions differ", ShareScreenText.of(ShareState.Searching("Versions differ")).detail)
    }

    @Test
    fun verifyingShowsCodeAndAsksToCompareUntilConfirmed() {
        val asking = ShareScreenText.of(ShareState.Verifying("Sam", "4821", false))
        assertEquals("Code 4821", asking.title)
        assertTrue(asking.hint.contains("up"))
        val waiting = ShareScreenText.of(ShareState.Verifying("Sam", "4821", true))
        assertEquals("Waiting for Sam to confirm", waiting.detail)
        assertTrue(waiting.hint.contains("cancel"))
    }

    @Test
    fun incomingOfferShowsKindNameCountsAndSize() {
        val text = ShareScreenText.of(ShareState.IncomingOffer("Sam", playlist, listOf(0, 2), 5 * 1024 * 1024L))
        assertEquals("Accept playlist Road Trip?", text.title)
        assertEquals("2 new of 3, 5.0 MB", text.detail)
        assertTrue(text.hint.contains("accept"))
    }

    @Test
    fun incomingOfferNamesEachKind() {
        fun title(kind: ShareKind) = ShareScreenText.of(
            ShareState.IncomingOffer("Sam", SessionHarness.manifest(kind, "a"), listOf(0), 1)
        ).title
        assertEquals("Accept song Mix?", title(ShareKind.SONG))
        assertEquals("Accept album Mix?", title(ShareKind.ALBUM))
    }

    @Test
    fun transferShowsDirectionCountsAndPercent() {
        val sending = ShareScreenText.of(ShareState.Transferring("Sam", playlist, true, 1, 3, 50, 200))
        assertEquals("Sending Road Trip", sending.title)
        assertEquals("1 of 3 songs (25%)", sending.detail)
        assertEquals("Receiving Road Trip", ShareScreenText.of(ShareState.Transferring("Sam", playlist, false, 0, 3, 0, 200)).title)
    }

    @Test
    fun transferWithUnknownTotalShowsNoPercent() {
        assertEquals("0 of 3 songs", ShareScreenText.of(ShareState.Transferring("Sam", playlist, true, 0, 3, 0, 0)).detail)
    }

    @Test
    fun lockedShowsReadyOrTheLastResult() {
        assertEquals("Ready", ShareScreenText.of(ShareState.Locked("Sam")).detail)
        assertEquals("Sent Mix: 3 new", ShareScreenText.of(ShareState.Locked("Sam", ShareResult.Sent("Mix", 3, 0))).detail)
        assertEquals(
            "Got Mix: 2 new, 1 failed",
            ShareScreenText.of(ShareState.Locked("Sam", ShareResult.Received("Mix", 2, 1, null))).detail
        )
    }

    @Test
    fun refusalsSaySoFromTheRightPointOfView() {
        fun detail(reason: DeclineReason, byPeer: Boolean) =
            ShareScreenText.of(ShareState.Locked("Sam", ShareResult.Refused(reason, byPeer))).detail
        assertEquals("They declined", detail(DeclineReason.USER, true))
        assertEquals("You declined", detail(DeclineReason.USER, false))
        assertEquals("They were busy", detail(DeclineReason.BUSY, true))
        assertEquals("They ran out of space", detail(DeclineReason.NO_SPACE, true))
        assertEquals("Not enough space", detail(DeclineReason.NO_SPACE, false))
    }

    @Test
    fun closedShowsReason() {
        assertEquals("Connection lost", ShareScreenText.of(ShareState.Closed("Connection lost")).detail)
        assertEquals("", ShareScreenText.of(ShareState.Closed()).detail)
    }

    @Test
    fun connectingAndOfferingNamePeerAndItem() {
        assertEquals("Connecting to Sam", ShareScreenText.of(ShareState.Connecting("Sam")).title)
        val offering = ShareScreenText.of(ShareState.Offering("Sam", playlist))
        assertEquals("Offering Road Trip", offering.title)
        assertEquals("Waiting for Sam", offering.detail)
    }

    @Test
    fun progressIsAFractionOnlyWhileTransferring() {
        assertEquals(0.25f, ShareScreenText.progress(ShareState.Transferring("Sam", playlist, true, 1, 3, 50, 200)))
        assertEquals(1f, ShareScreenText.progress(ShareState.Transferring("Sam", playlist, true, 1, 3, 500, 200)))
        assertNull(ShareScreenText.progress(ShareState.Transferring("Sam", playlist, true, 0, 3, 0, 0)))
        assertNull(ShareScreenText.progress(ShareState.Locked("Sam")))
    }

    @Test
    fun everyDragAllowedStateHasAHint() {
        val states = listOf(
            ShareState.Searching(), ShareState.Connecting("S"), ShareState.Verifying("S", "1", false),
            ShareState.Locked("S"), ShareState.Offering("S", playlist),
            ShareState.IncomingOffer("S", playlist, listOf(0), 1), ShareState.Transferring("S", playlist, true, 0, 1, 0, 1)
        )
        states.forEach { assertTrue(ShareScreenText.of(it).hint.isNotEmpty(), "$it") }
    }
}
