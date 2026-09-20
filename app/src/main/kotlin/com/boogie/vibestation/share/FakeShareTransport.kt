package com.boogie.vibestation.share

import java.io.InputStream

/**
 * A [ShareTransport] with no radio. It records everything the session asks of it and lets a test, or the
 * debug simulator, play the other phone by calling the `simulate*` functions.
 */
@Suppress("TooManyFunctions") // mirrors the transport interface plus one simulate function per event
internal class FakeShareTransport : ShareTransport {

    private var listener: ShareTransport.Listener? = null

    /** The hello passed to [start], or null before it. */
    var hello: String? = null
        private set

    /** True between [start] and [stop]. */
    var running = false
        private set

    /** Peers the session asked to connect to. */
    val requested = mutableListOf<String>()

    /** Peers whose code the session confirmed. */
    val accepted = mutableListOf<String>()

    /** Peers whose code the session rejected. */
    val rejected = mutableListOf<String>()

    /** Peers the session disconnected. */
    val disconnected = mutableListOf<String>()

    /** Control messages the session sent, in order. */
    val messages = mutableListOf<String>()

    /** Files the session sent, in order. */
    val files = mutableListOf<ShareFileSource>()

    override fun start(hello: String, listener: ShareTransport.Listener) {
        this.hello = hello
        this.listener = listener
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun requestConnection(peerId: String) {
        requested += peerId
    }

    override fun acceptConnection(peerId: String) {
        accepted += peerId
    }

    override fun rejectConnection(peerId: String) {
        rejected += peerId
    }

    override fun disconnect(peerId: String) {
        disconnected += peerId
    }

    override fun sendMessage(peerId: String, text: String) {
        messages += text
    }

    override fun sendFile(peerId: String, source: ShareFileSource) {
        files += source
    }

    /** Plays a nearby phone appearing. */
    fun simulatePeerFound(peerId: String, peerName: String) = listener?.onPeerFound(peerId, peerName)

    /** Plays a found phone going away. */
    fun simulatePeerLost(peerId: String) = listener?.onPeerLost(peerId)

    /** Plays a nearby phone requesting a connection, or the connection being set up. */
    fun simulateConnectionInitiated(peerId: String, peerName: String, code: String) =
        listener?.onConnectionInitiated(peerId, peerName, code)

    /** Plays both sides confirming the code. */
    fun simulateConnected(peerId: String) = listener?.onConnected(peerId)

    /** Plays the connection failing to open. */
    fun simulateConnectionFailed(peerId: String, reason: String) = listener?.onConnectionFailed(peerId, reason)

    /** Plays the connection closing. */
    fun simulateDisconnected(peerId: String) = listener?.onDisconnected(peerId)

    /** Plays the peer sending a control message. */
    fun simulateMessage(peerId: String, message: ShareMessage) = listener?.onMessage(peerId, message.toJson())

    /** Plays the peer sending raw text, which may be malformed. */
    fun simulateRawMessage(peerId: String, text: String) = listener?.onMessage(peerId, text)

    /** Plays a track arriving from the peer. */
    fun simulateFileReceived(peerId: String, index: Int, input: InputStream) =
        listener?.onFileReceived(peerId, index, input)

    /** Plays transfer progress. */
    fun simulateProgress(index: Int, bytes: Long, total: Long, sending: Boolean) =
        listener?.onFileProgress(index, bytes, total, sending)

    /** Plays a failed file transfer. */
    fun simulateFileFailed(index: Int) = listener?.onFileFailed(index)
}
