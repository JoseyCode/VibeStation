package com.boogie.vibestation.share

import java.io.InputStream

/**
 * A local file to send.
 *
 * @property index     Position of the track in the offered manifest; the receiver gets it back with the file.
 * @property sizeBytes Size of the file, for progress.
 * @property open      Opens a fresh stream of the file's bytes; the transport closes it.
 */
internal class ShareFileSource(val index: Int, val sizeBytes: Long, val open: () -> InputStream)

/**
 * The radio layer under Share Mode, kept minimal so the session logic can be tested without one. The
 * real implementation wraps Google Nearby Connections; [FakeShareTransport] stands in for tests and the
 * debug simulator. Contract: after [start] the transport advertises and discovers until [stop], and
 * resumes doing so when a connection ends. Calls may come from any thread; listener callbacks may arrive
 * on any thread too, so the [ShareSession] serializes them.
 */
internal interface ShareTransport {

    /** Events from the radio layer. */
    interface Listener {
        /** A nearby phone advertising Share Mode appeared; [peerName] is its advertised [ShareHello] text. */
        fun onPeerFound(peerId: String, peerName: String)

        /** A previously found phone went away. */
        fun onPeerLost(peerId: String)

        /** A connection is being set up; both users should compare [code], the short authentication code. */
        fun onConnectionInitiated(peerId: String, peerName: String, code: String)

        /** Both sides confirmed and the connection is open. */
        fun onConnected(peerId: String)

        /** The connection could not be established (rejected, timed out, radio error). */
        fun onConnectionFailed(peerId: String, reason: String)

        /** An open connection ended. */
        fun onDisconnected(peerId: String)

        /** A control message arrived; see [ShareMessage]. */
        fun onMessage(peerId: String, text: String)

        /** Track [index] finished arriving; [input] holds its bytes and is closed by the caller. */
        fun onFileReceived(peerId: String, index: Int, input: InputStream)

        /** Progress of the file [index], being [sending] or receiving. */
        fun onFileProgress(index: Int, bytes: Long, total: Long, sending: Boolean)

        /** The transfer of file [index] failed. */
        fun onFileFailed(index: Int)
    }

    /** Starts advertising [hello] and discovering, reporting to [listener]. */
    fun start(hello: String, listener: Listener)

    /** Stops advertising, discovery, and any connection. */
    fun stop()

    /** Asks the found phone [peerId] to connect. */
    fun requestConnection(peerId: String)

    /** Confirms the authentication code for [peerId]. */
    fun acceptConnection(peerId: String)

    /** Rejects [peerId] after seeing its authentication code. */
    fun rejectConnection(peerId: String)

    /** Closes the connection to [peerId]. */
    fun disconnect(peerId: String)

    /** Sends a control message [text] (see [ShareMessage]) to [peerId]. */
    fun sendMessage(peerId: String, text: String)

    /** Sends the file described by [source] to [peerId]. */
    fun sendFile(peerId: String, source: ShareFileSource)
}
