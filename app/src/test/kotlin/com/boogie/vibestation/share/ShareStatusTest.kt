package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareStatusTest {
    private val manifest = SessionHarness.manifest(ShareKind.PLAYLIST, "a", "b", name = "Road Trip")

    @Test
    fun searchingAndIdleAreDescribedTheSame() {
        assertEquals(ShareStatus.text(ShareState.Idle), ShareStatus.text(ShareState.Searching("notice")))
    }

    @Test
    fun peerStatesNameThePeer() {
        assertEquals("Connecting to Sam", ShareStatus.text(ShareState.Connecting("Sam")))
        assertEquals("Check the code with Sam", ShareStatus.text(ShareState.Verifying("Sam", "1234", false)))
        assertEquals("Connected to Sam", ShareStatus.text(ShareState.Locked("Sam")))
        assertEquals("Waiting for Sam to answer", ShareStatus.text(ShareState.Offering("Sam", manifest)))
    }

    @Test
    fun incomingOfferNamesPeerAndItem() {
        assertEquals(
            "Sam wants to share Road Trip",
            ShareStatus.text(ShareState.IncomingOffer("Sam", manifest, listOf(0), 10))
        )
    }

    @Test
    fun transferShowsDirectionAndProgress() {
        assertEquals(
            "Sending Road Trip (1 of 2)",
            ShareStatus.text(ShareState.Transferring("Sam", manifest, true, 1, 2, 5, 10))
        )
        assertEquals(
            "Receiving Road Trip (0 of 2)",
            ShareStatus.text(ShareState.Transferring("Sam", manifest, false, 0, 2, 0, 10))
        )
    }

    @Test
    fun closedSaysItIsOff() {
        assertTrue(ShareStatus.text(ShareState.Closed("lost")).contains("off"))
    }
}
