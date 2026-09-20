package com.boogie.vibestation.share

import com.boogie.vibestation.share.SessionHarness.Companion.CODE
import com.boogie.vibestation.share.SessionHarness.Companion.PEER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests for how [ShareSession] finds, pairs with and leaves a peer. */
class ShareSessionPairingTest {

    private val h = SessionHarness()

    /** Starting advertises this phone's hello and searches. */
    @Test
    fun startBeginsSearching() {
        h.session.start()

        assertEquals(ShareState.Searching(), h.state)
        assertEquals(h.me.encode(), h.transport.hello)
        assertTrue(h.transport.running)
    }

    /** When the found phone has the larger nonce, this phone sends the connection request. */
    @Test
    fun smallerNonceRequestsConnection() {
        h.session.start()

        h.transport.simulatePeerFound(PEER, h.peer.encode())

        assertEquals(listOf(PEER), h.transport.requested)
        assertEquals(ShareState.Connecting("Peer"), h.state)
    }

    /** When the found phone has the smaller nonce, this phone waits for its request instead of sending one. */
    @Test
    fun largerNonceWaits() {
        val larger = SessionHarness(myNonce = "ffffffff")
        larger.session.start()

        larger.transport.simulatePeerFound(PEER, larger.peer.copy(nonce = "00000000").encode())

        assertTrue(larger.transport.requested.isEmpty())
        assertEquals(ShareState.Searching(), larger.state)
    }

    /** A phone on another app version is not connected to, and the user is told why. */
    @Test
    fun versionMismatchIsReportedNotConnected() {
        h.session.start()

        h.transport.simulatePeerFound(PEER, ShareHello(SessionHarness.VERSION + 1, "ffffffff", "Old").encode())

        assertTrue(h.transport.requested.isEmpty())
        assertIs<ShareState.Searching>(h.state)
        assertTrue((h.state as ShareState.Searching).notice!!.contains("different"))
    }

    /** Advertisements that are not VibeStation's are ignored. */
    @Test
    fun foreignPeerIsIgnored() {
        h.session.start()

        h.transport.simulatePeerFound(PEER, "Someone's Speaker")

        assertTrue(h.transport.requested.isEmpty())
        assertEquals(ShareState.Searching(), h.state)
    }

    /** Both phones see the code; confirming it with yes accepts, and the open connection becomes Locked. */
    @Test
    fun confirmCodeThenLock() {
        h.session.start()
        h.transport.simulateConnectionInitiated(PEER, h.peer.encode(), CODE)
        assertEquals(ShareState.Verifying("Peer", CODE, confirmed = false), h.state)

        h.session.yes()
        assertEquals(listOf(PEER), h.transport.accepted)
        assertEquals(ShareState.Verifying("Peer", CODE, confirmed = true), h.state)

        h.transport.simulateConnected(PEER)
        assertEquals(ShareState.Locked("Peer"), h.state)
    }

    /** Down on the code rejects the connection and goes back to searching. */
    @Test
    fun rejectCodeReturnsToSearching() {
        h.session.start()
        h.transport.simulateConnectionInitiated(PEER, h.peer.encode(), CODE)

        h.session.no()

        assertEquals(listOf(PEER), h.transport.rejected)
        assertEquals(ShareState.Searching(), h.state)
    }

    /** A connection from a mismatched-version phone, or while another pairing is under way, is rejected. */
    @Test
    fun unwantedConnectionsAreRejected() {
        h.session.start()
        h.transport.simulateConnectionInitiated("old", ShareHello(1, "ffffffff", "Old").encode(), CODE)
        assertEquals(listOf("old"), h.transport.rejected)
        assertEquals(ShareState.Searching(), h.state)

        h.transport.simulateConnectionInitiated(PEER, h.peer.encode(), CODE)
        h.transport.simulateConnectionInitiated("other", h.peer.encode(), CODE)

        assertEquals(listOf("old", "other"), h.transport.rejected)
        assertEquals(ShareState.Verifying("Peer", CODE, confirmed = false), h.state)
    }

    /** Yes twice on the code does not confirm twice. */
    @Test
    fun confirmingTwiceAcceptsOnce() {
        h.session.start()
        h.transport.simulateConnectionInitiated(PEER, h.peer.encode(), CODE)

        h.session.yes()
        h.session.yes()

        assertEquals(1, h.transport.accepted.size)
    }

    /** A failed connection attempt returns to searching with the reason. */
    @Test
    fun connectionFailureReturnsToSearching() {
        h.session.start()
        h.transport.simulatePeerFound(PEER, h.peer.encode())

        h.transport.simulateConnectionFailed(PEER, "timed out")

        assertEquals(ShareState.Searching("timed out"), h.state)
    }

    /** Losing sight of the phone being connected to abandons that attempt. */
    @Test
    fun peerLostWhileConnecting() {
        h.session.start()
        h.transport.simulatePeerFound(PEER, h.peer.encode())

        h.transport.simulatePeerLost(PEER)

        assertEquals(ShareState.Searching(), h.state)
    }

    /** While locked, yes asks the UI for the item picker and no closes the connection. */
    @Test
    fun lockedYesPicksAndNoCloses() {
        h.connect()

        h.session.yes()
        assertEquals(1, h.pickRequests)
        assertEquals(ShareState.Locked("Peer"), h.state)

        h.session.no()
        assertEquals(listOf(PEER), h.transport.disconnected)
        assertEquals(ShareState.Searching(), h.state)
    }

    /** The peer going away while idle returns quietly to searching; the disconnect we caused is not repeated. */
    @Test
    fun peerDisconnectWhileIdle() {
        h.connect()

        h.transport.simulateDisconnected(PEER)

        assertEquals(ShareState.Searching(), h.state)
        assertTrue(h.transport.disconnected.isEmpty())
    }

    /** Down while searching leaves Share Mode and stops the radio; later input does nothing. */
    @Test
    fun noWhileSearchingExits() {
        h.session.start()

        h.session.no()

        assertEquals(ShareState.Closed(), h.state)
        assertFalse(h.transport.running)
        h.session.yes()
        h.session.no()
        assertEquals(ShareState.Closed(), h.state)
    }

    /** Stopping mid-connection disconnects the peer first. */
    @Test
    fun stopDisconnectsPeer() {
        h.connect()

        h.session.stop()

        assertEquals(listOf(PEER), h.transport.disconnected)
        assertEquals(ShareState.Closed(), h.state)
        assertFalse(h.transport.running)
    }

    /** Which gestures each screen accepts. */
    @Test
    fun allowedGesturesPerState() {
        val manifest = SessionHarness.manifest(ShareKind.SONG, "A")
        val yesAndNo = listOf(
            ShareState.Verifying("P", "1", false),
            ShareState.Locked("P"),
            ShareState.IncomingOffer("P", manifest, emptyList(), 0)
        )
        val onlyNo = listOf(
            ShareState.Searching(),
            ShareState.Connecting("P"),
            ShareState.Verifying("P", "1", true),
            ShareState.Offering("P", manifest),
            ShareState.Transferring("P", manifest, true, 0, 1, 0, 1)
        )
        val neither = listOf(ShareState.Idle, ShareState.Closed())

        yesAndNo.forEach { assertTrue(it.allowsYes && it.allowsNo, "$it") }
        onlyNo.forEach { assertTrue(!it.allowsYes && it.allowsNo, "$it") }
        neither.forEach { assertTrue(!it.allowsYes && !it.allowsNo, "$it") }
    }
}
