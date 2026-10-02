package com.boogie.vibestation.share

import com.boogie.vibestation.share.SessionHarness.Companion.PEER
import com.boogie.vibestation.share.SessionHarness.Companion.bytes
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for [ShareSession] when this phone is the one receiving. */
class ShareSessionReceiveTest {

    private val h = SessionHarness()
    private val playlist = SessionHarness.manifest(ShareKind.PLAYLIST, "A", "B", "C")

    private class TrackedStream : ByteArrayInputStream(byteArrayOf(1)) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun ownB() {
        h.host.library = mutableListOf(LocalTrack("7", "B", "Artist", 1000))
    }

    private fun receiveOffer(manifest: ShareManifest = playlist) {
        h.connect()
        h.transport.simulateMessage(PEER, ShareMessage.Offer(manifest))
    }

    private fun savedIds(): List<String> {
        val songs = h.host.playlists.single().getJSONArray("songData")
        return (0 until songs.length()).map { songs.getJSONObject(it).getString("id") }
    }

    /** The prompt lists exactly the tracks this phone lacks and what they cost. */
    @Test
    fun offerShowsWhatIsMissing() {
        ownB()

        receiveOffer()

        assertEquals(ShareState.IncomingOffer("Peer", playlist, listOf(0, 2), 20), h.state)
    }

    /** Accepting asks for only the missing tracks; arriving files are stored and the playlist uses local ids. */
    @Test
    fun fullReceiveBuildsPlaylistWithLocalIds() {
        ownB()
        receiveOffer()

        h.session.yes()
        assertEquals(ShareMessage.Accept(listOf(0, 2)), h.sent().last())
        assertEquals(ShareState.Transferring("Peer", playlist, false, 0, 2, 0, 20), h.state)

        h.transport.simulateProgress(0, 4, 10, false)
        assertEquals(ShareState.Transferring("Peer", playlist, false, 0, 2, 4, 20), h.state)

        h.transport.simulateFileReceived(PEER, 0, bytes(9, 9))
        assertEquals(ShareState.Transferring("Peer", playlist, false, 1, 2, 10, 20), h.state)

        h.transport.simulateFileReceived(PEER, 2, bytes(5))

        assertEquals(listOf("A", "C"), h.host.stored.map { it.first.title })
        assertEquals(listOf<Byte>(9, 9), h.host.stored[0].second)
        assertEquals(listOf("100", "7", "101"), savedIds())
        assertEquals(ShareMessage.Done(2, 0), h.sent().last())
        assertEquals(ShareState.Locked("Peer", ShareResult.Received("Mix", 2, 0, "Mix")), h.state)
    }

    /** A clashing playlist name is made unique and the cover is stored. */
    @Test
    fun playlistGetsUniqueNameAndCover() {
        h.host.existingNames = mutableListOf("Mix")
        h.host.cover = "file:///cover.jpg"
        val withCover = playlist.copy(coverBase64 = "QUJD")
        receiveOffer(withCover)
        h.session.yes()

        for (index in 0..2) h.transport.simulateFileReceived(PEER, index, bytes(1))

        val entry = h.host.playlists.single()
        assertEquals("Mix (2)", entry.getString("name"))
        assertEquals("file:///cover.jpg", entry.getString("imageUri"))
        assertEquals("desc", entry.getString("description"))
        assertEquals(listOf("QUJD"), h.host.coversRequested)
    }

    /** When everything is already owned nothing is requested, yet the playlist is still created. */
    @Test
    fun everythingOwnedStillCreatesPlaylist() {
        h.host.library = mutableListOf(
            LocalTrack("1", "A", "Artist", 1000), LocalTrack("2", "B", "Artist", 1000), LocalTrack("3", "C", "Artist", 1000)
        )
        receiveOffer()

        h.session.yes()

        assertEquals(listOf<ShareMessage>(ShareMessage.Accept(emptyList()), ShareMessage.Done(0, 0)), h.sent())
        assertEquals(listOf("1", "2", "3"), savedIds())
        assertEquals(ShareState.Locked("Peer", ShareResult.Received("Mix", 0, 0, "Mix")), h.state)
        assertTrue(h.host.stored.isEmpty())
    }

    /** Songs and albums are stored but never create a playlist. */
    @Test
    fun albumCreatesNoPlaylist() {
        val album = SessionHarness.manifest(ShareKind.ALBUM, "A", name = "Record")
        receiveOffer(album)
        h.session.yes()

        h.transport.simulateFileReceived(PEER, 0, bytes(1))

        assertTrue(h.host.playlists.isEmpty())
        assertEquals(ShareState.Locked("Peer", ShareResult.Received("Record", 1, 0, null)), h.state)
    }

    /** Declining tells the sender and stores nothing. */
    @Test
    fun declineSendsDecline() {
        receiveOffer()

        h.session.no()

        assertEquals(ShareMessage.Decline(DeclineReason.USER), h.sent().last())
        assertEquals(ShareState.Locked("Peer", ShareResult.Refused(DeclineReason.USER, byPeer = false)), h.state)
        h.transport.simulateFileReceived(PEER, 0, bytes(1))
        assertTrue(h.host.stored.isEmpty())
    }

    /** An offer that does not fit in free storage is refused automatically. */
    @Test
    fun notEnoughSpaceIsDeclined() {
        h.host.free = 19

        receiveOffer()

        assertEquals(ShareMessage.Decline(DeclineReason.NO_SPACE), h.sent().last())
        assertEquals(ShareState.Locked("Peer", ShareResult.Refused(DeclineReason.NO_SPACE, byPeer = false)), h.state)
    }

    /** The space check counts only what is missing, so an offer of mostly-owned tracks still fits. */
    @Test
    fun spaceCheckCountsOnlyMissingTracks() {
        ownB()
        h.host.free = 20

        receiveOffer()

        assertEquals(ShareState.IncomingOffer("Peer", playlist, listOf(0, 2), 20), h.state)
    }

    /** A second offer while one is awaiting an answer is turned away as busy. */
    @Test
    fun secondOfferWhilePendingIsBusy() {
        receiveOffer()

        h.transport.simulateMessage(PEER, ShareMessage.Offer(SessionHarness.manifest(ShareKind.SONG, "Z")))

        assertEquals(ShareMessage.Decline(DeclineReason.BUSY), h.sent().last())
        assertEquals(ShareState.IncomingOffer("Peer", playlist, listOf(0, 1, 2), 30), h.state)
    }

    /** Files nobody asked for are dropped and closed, never stored. */
    @Test
    fun unsolicitedFilesAreIgnoredAndClosed() {
        ownB()
        receiveOffer()
        val early = TrackedStream()
        h.transport.simulateFileReceived(PEER, 0, early)
        assertTrue(early.closed)

        h.session.yes()
        val notRequested = TrackedStream()
        val outOfRange = TrackedStream()
        h.transport.simulateFileReceived(PEER, 1, notRequested)
        h.transport.simulateFileReceived(PEER, 42, outOfRange)
        h.transport.simulateFileReceived("stranger", 0, TrackedStream())
        h.transport.simulateFileReceived(PEER, 0, bytes(1))
        val duplicate = TrackedStream()
        h.transport.simulateFileReceived(PEER, 0, duplicate)

        assertTrue(notRequested.closed && outOfRange.closed && duplicate.closed)
        assertEquals(listOf("A"), h.host.stored.map { it.first.title })
    }

    /** The received stream is closed after storing. */
    @Test
    fun receivedStreamIsClosed() {
        receiveOffer()
        h.session.yes()
        val stream = TrackedStream()

        h.transport.simulateFileReceived(PEER, 0, stream)

        assertTrue(stream.closed)
    }

    /** One track failing to store is counted and the rest still import; the playlist skips only the failed one. */
    @Test
    fun storageFailureIsCountedNotFatal() {
        ownB()
        h.host.failTitles += "A"
        receiveOffer()
        h.session.yes()

        h.transport.simulateFileReceived(PEER, 0, bytes(1))
        h.transport.simulateFileReceived(PEER, 2, bytes(1))

        assertEquals(listOf("7", "100"), savedIds())
        assertEquals(ShareMessage.Done(1, 1), h.sent().last())
        assertEquals(ShareState.Locked("Peer", ShareResult.Received("Mix", 1, 1, "Mix")), h.state)
    }

    /** A track whose transfer fails is counted and the import still completes. */
    @Test
    fun failedTransferIsCounted() {
        ownB()
        receiveOffer()
        h.session.yes()

        h.transport.simulateFileFailed(0)
        h.transport.simulateFileReceived(PEER, 2, bytes(1))

        assertEquals(ShareState.Locked("Peer", ShareResult.Received("Mix", 1, 1, "Mix")), h.state)
        assertEquals(listOf("7", "100"), savedIds())
    }

    /** If the playlist cannot be saved the tracks still count, but no playlist is reported. */
    @Test
    fun playlistSaveFailureIsReported() {
        h.host.failAddPlaylist = true
        receiveOffer(SessionHarness.manifest(ShareKind.PLAYLIST, "A"))
        h.session.yes()

        h.transport.simulateFileReceived(PEER, 0, bytes(1))

        val result = (h.state as ShareState.Locked).lastResult as ShareResult.Received
        assertEquals(1, result.stored)
        assertNull(result.playlistName)
    }

    /** Losing the connection mid-import abandons it; stragglers are ignored. */
    @Test
    fun disconnectMidReceiveAbandons() {
        receiveOffer()
        h.session.yes()
        h.transport.simulateFileReceived(PEER, 0, bytes(1))

        h.transport.simulateDisconnected(PEER)
        val straggler = TrackedStream()
        h.transport.simulateFileReceived(PEER, 1, straggler)

        assertEquals(ShareState.Searching("Connection lost"), h.state)
        assertTrue(straggler.closed)
        assertEquals(1, h.host.stored.size)
        assertTrue(h.host.playlists.isEmpty())
    }

    /** Yes on an offer is ignored once the answer has been given. */
    @Test
    fun yesTwiceAcceptsOnce() {
        receiveOffer()
        h.session.yes()
        val before = h.transport.messages.size

        h.session.yes()

        assertEquals(before, h.transport.messages.size)
        assertFalse(h.state is ShareState.IncomingOffer)
    }

    /** A malformed offer from the peer drops the connection without touching storage. */
    @Test
    fun malformedOfferDropsConnection() {
        h.connect()

        h.transport.simulateRawMessage(PEER, """{"type":"offer","manifest":"{}"}""")

        assertEquals(ShareState.Searching("The other phone sent invalid data"), h.state)
        assertTrue(h.host.stored.isEmpty())
    }
}
