package com.boogie.vibestation.share

import com.boogie.vibestation.share.SessionHarness.Companion.PEER
import com.boogie.vibestation.share.SessionHarness.Companion.bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for [ShareSession] when this phone is the one sending. */
class ShareSessionSendTest {

    private val h = SessionHarness()
    private val manifest = SessionHarness.manifest(ShareKind.ALBUM, "A", "B", "C", name = "Record")
    private val opened = mutableListOf<Int>()
    private val offer = ShareOffer(manifest) { index ->
        opened += index
        bytes(index, index)
    }

    private fun startOffer() {
        h.connect()
        h.session.offer(offer)
    }

    /** Offering sends the manifest and waits for an answer. */
    @Test
    fun offerSendsManifestAndWaits() {
        startOffer()

        assertEquals(listOf<ShareMessage>(ShareMessage.Offer(manifest)), h.sent())
        assertEquals(ShareState.Offering("Peer", manifest), h.state)
    }

    /** An offer made when there is no open, idle connection is ignored. */
    @Test
    fun offerIgnoredUnlessLocked() {
        h.session.start()

        h.session.offer(offer)

        assertTrue(h.transport.messages.isEmpty())
        assertEquals(ShareState.Searching(), h.state)
    }

    /** Only the tracks the receiver asked for are sent, in order, and each opens the right file lazily. */
    @Test
    fun acceptSendsOnlyRequestedFiles() {
        startOffer()

        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(2, 0)))

        assertEquals(ShareState.Transferring("Peer", manifest, true, 0, 2, 0, 20), h.state)
        assertEquals(listOf(0, 2), h.transport.files.map { it.index })
        assertEquals(listOf(10L, 10L), h.transport.files.map { it.sizeBytes })
        assertTrue(opened.isEmpty())
        assertEquals(listOf<Byte>(2, 2), h.transport.files[1].open().readBytes().toList())
        assertEquals(listOf(2), opened)
    }

    /** Progress reports fill in bytes and count finished files. */
    @Test
    fun progressUpdatesTransferState() {
        startOffer()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(0, 2)))

        h.transport.simulateProgress(0, 10, 10, true)
        h.transport.simulateProgress(2, 4, 10, true)

        assertEquals(ShareState.Transferring("Peer", manifest, true, 1, 2, 14, 20), h.state)
    }

    /** The receiver's Done ends the transfer with the outcome and leaves the phones connected and idle. */
    @Test
    fun doneReturnsToLockedWithResult() {
        startOffer()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(0, 2)))

        h.transport.simulateMessage(PEER, ShareMessage.Done(stored = 1, failed = 1))

        assertEquals(ShareState.Locked("Peer", ShareResult.Sent("Record", 1, 1)), h.state)
    }

    /** A receiver that already has everything accepts an empty list and just confirms. */
    @Test
    fun acceptingNothingStillCompletes() {
        startOffer()

        h.transport.simulateMessage(PEER, ShareMessage.Accept(emptyList()))
        assertTrue(h.transport.files.isEmpty())
        assertEquals(ShareState.Transferring("Peer", manifest, true, 0, 0, 0, 0), h.state)

        h.transport.simulateMessage(PEER, ShareMessage.Done(0, 0))
        assertEquals(ShareState.Locked("Peer", ShareResult.Sent("Record", 0, 0)), h.state)
    }

    /** After a completed send either phone can offer again on the same connection. */
    @Test
    fun canSendAgainAfterDone() {
        startOffer()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(emptyList()))
        h.transport.simulateMessage(PEER, ShareMessage.Done(0, 0))

        h.session.offer(offer)

        assertEquals(ShareState.Offering("Peer", manifest), h.state)
    }

    /** Requests for tracks that are not in the offer, or repeated ones, are a protocol violation. */
    @Test
    fun invalidAcceptDropsConnection() {
        for (indices in listOf(listOf(3), listOf(0, 0), listOf(1, 5))) {
            val session = SessionHarness()
            session.connect()
            session.session.offer(offer)

            session.transport.simulateMessage(PEER, ShareMessage.Accept(indices))

            assertEquals(ShareState.Searching("The other phone sent invalid data"), session.state, "$indices")
            assertEquals(listOf(PEER), session.transport.disconnected)
            assertTrue(session.transport.files.isEmpty())
        }
    }

    /** A decline reports why and returns to idle. */
    @Test
    fun declineReturnsToLocked() {
        startOffer()

        h.transport.simulateMessage(PEER, ShareMessage.Decline(DeclineReason.NO_SPACE))

        assertEquals(ShareState.Locked("Peer", ShareResult.Refused(DeclineReason.NO_SPACE, byPeer = true)), h.state)
    }

    /** Answers that make no sense in the current state change nothing. */
    @Test
    fun unexpectedRepliesAreIgnored() {
        h.connect()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(0)))
        h.transport.simulateMessage(PEER, ShareMessage.Done(1, 0))
        h.transport.simulateMessage(PEER, ShareMessage.Decline(DeclineReason.USER))

        assertEquals(ShareState.Locked("Peer"), h.state)
        assertTrue(h.transport.files.isEmpty())
    }

    /** Messages from some other endpoint never affect the session. */
    @Test
    fun messagesFromOtherPeerIgnored() {
        startOffer()

        h.transport.simulateMessage("stranger", ShareMessage.Accept(listOf(0)))

        assertEquals(ShareState.Offering("Peer", manifest), h.state)
        assertTrue(h.transport.files.isEmpty())
    }

    /** If both phones offer at once, the second offer is turned away as busy and this offer stays pending. */
    @Test
    fun simultaneousOfferIsBusy() {
        startOffer()

        h.transport.simulateMessage(PEER, ShareMessage.Offer(SessionHarness.manifest(ShareKind.SONG, "X")))

        assertEquals(ShareMessage.Decline(DeclineReason.BUSY), h.sent().last())
        assertEquals(ShareState.Offering("Peer", manifest), h.state)
    }

    /** Text that is not a valid message drops the connection instead of crashing. */
    @Test
    fun malformedMessageDropsConnection() {
        startOffer()

        h.transport.simulateRawMessage(PEER, "{not json")

        assertEquals(ShareState.Searching("The other phone sent invalid data"), h.state)
        assertEquals(listOf(PEER), h.transport.disconnected)
    }

    /** A failed outgoing file abandons the send. */
    @Test
    fun outgoingFileFailureDropsConnection() {
        startOffer()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(0)))

        h.transport.simulateFileFailed(0)

        assertEquals(ShareState.Searching("Transfer failed"), h.state)
        assertEquals(listOf(PEER), h.transport.disconnected)
    }

    /** The connection dying mid-transfer is reported as lost. */
    @Test
    fun disconnectMidTransferIsReported() {
        startOffer()
        h.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(0)))

        h.transport.simulateDisconnected(PEER)

        assertEquals(ShareState.Searching("Connection lost"), h.state)
    }

    /** Down while waiting or transferring closes the connection. */
    @Test
    fun noCancelsOfferAndTransfer() {
        startOffer()
        h.session.no()
        assertEquals(ShareState.Searching(), h.state)
        assertEquals(listOf(PEER), h.transport.disconnected)

        val other = SessionHarness()
        other.connect()
        other.session.offer(offer)
        other.transport.simulateMessage(PEER, ShareMessage.Accept(listOf(1)))
        other.session.no()
        assertEquals(ShareState.Searching(), other.state)
    }

    /** Yes has no meaning while waiting on the peer. */
    @Test
    fun yesWhileOfferingDoesNothing() {
        startOffer()

        h.session.yes()

        assertEquals(ShareState.Offering("Peer", manifest), h.state)
        assertEquals(0, h.pickRequests)
    }
}
