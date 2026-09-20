package com.boogie.vibestation.share

import java.util.concurrent.Executor

/** Bookkeeping for the offer this phone is sending. */
private class OutgoingShare(val offer: ShareOffer) {
    var indices: List<Int> = emptyList()
    private val sent = HashMap<Int, Long>()

    val bytesTotal: Long get() = indices.sumOf { size(it) }
    val bytesDone: Long get() = indices.sumOf { sent[it] ?: 0L }
    val doneFiles: Int get() = indices.count { (sent[it] ?: 0L) >= size(it) }

    fun progress(index: Int, bytes: Long) {
        if (index in indices) sent[index] = bytes
    }

    private fun size(index: Int): Long = offer.manifest.tracks[index].sizeBytes
}

/**
 * The whole conversation of Share Mode as a state machine, with no Android in it: it turns transport
 * events and the user's yes/no into [ShareState] changes and protocol messages. It finds a peer, pairs
 * with a confirmed code, then lets either phone offer a song, album or playlist; the receiver works out
 * what it lacks, accepts, gets exactly those files, and records the playlist with its own song ids.
 *
 * Every entry point hops onto [executor], which must run tasks one at a time (a single thread, or a
 * direct executor in tests), so state is only ever touched from one place. [listener] is called from that
 * executor too, and the UI is expected to forward to the main thread.
 *
 * @param transport The radio layer.
 * @param host      Library, storage and playlists of this device.
 * @param hello     This phone's identity; its version must equal the peer's to pair.
 * @param executor  Serial executor all work is funnelled through.
 * @param listener  Receives state changes and requests for the UI.
 */
@Suppress("TooManyFunctions") // one state machine; splitting it would scatter the transitions
internal class ShareSession(
    private val transport: ShareTransport,
    private val host: ShareHost,
    private val hello: ShareHello,
    private val executor: Executor,
    private val listener: Listener
) {

    /** What the session reports to the UI. */
    interface Listener {
        /** The state changed. */
        fun onState(state: ShareState)

        /** The user said yes while idle and connected: show the item picker, then call [offer]. */
        fun onPickRequested()
    }

    /** Current state; written only on the executor. */
    @Volatile
    var state: ShareState = ShareState.Idle
        private set

    private var peerId: String? = null
    private var peerName = ""
    private var outgoing: OutgoingShare? = null
    private var incoming: IncomingImport? = null

    /** Begins advertising and discovering. */
    fun start() = post {
        transport.start(hello.encode(), Events())
        setState(ShareState.Searching())
    }

    /** Leaves Share Mode: drops any connection and stops the radio. */
    fun stop() = post { shutdown(null) }

    /** The user dragged up: confirm the code, pick something to send, or accept the incoming offer. */
    fun yes() = post {
        when (val current = state) {
            is ShareState.Verifying -> if (!current.confirmed) confirmCode(current)
            is ShareState.Locked -> listener.onPickRequested()
            is ShareState.IncomingOffer -> acceptIncoming()
            else -> Unit
        }
    }

    /** The user dragged down: back out of whatever is going on, one step at a time. */
    fun no() = post {
        when (val current = state) {
            is ShareState.Searching -> shutdown(null)
            is ShareState.Verifying -> rejectCode()
            is ShareState.IncomingOffer -> declineIncoming(current)
            is ShareState.Connecting, is ShareState.Locked, is ShareState.Offering, is ShareState.Transferring ->
                dropConnection(null)
            else -> Unit
        }
    }

    /**
     * Offers something to the connected phone. Ignored unless the session is connected and idle.
     *
     * @param offer The prepared share.
     */
    fun offer(offer: ShareOffer) = post {
        val id = peerId
        if (state is ShareState.Locked && id != null) {
            outgoing = OutgoingShare(offer)
            transport.sendMessage(id, ShareMessage.Offer(offer.manifest).toJson())
            setState(ShareState.Offering(peerName, offer.manifest))
        }
    }

    private fun post(task: () -> Unit) = executor.execute(task)

    /** The import in progress if [peerId] is the connected peer and files are expected; otherwise null. */
    private fun activeImport(peerId: String): IncomingImport? =
        incoming.takeIf { this.peerId == peerId && state is ShareState.Transferring }

    private fun setState(new: ShareState) {
        state = new
        listener.onState(new)
    }

    private fun shutdown(reason: String?) {
        peerId?.let(transport::disconnect)
        peerId = null
        outgoing = null
        incoming = null
        transport.stop()
        setState(ShareState.Closed(reason))
    }

    /** Ends the connection but keeps searching, so the user can pair again. */
    private fun dropConnection(notice: String?) {
        peerId?.let(transport::disconnect)
        backToSearching(notice)
    }

    private fun backToSearching(notice: String?) {
        peerId = null
        outgoing = null
        incoming = null
        setState(ShareState.Searching(notice))
    }

    private fun confirmCode(current: ShareState.Verifying) {
        peerId?.let(transport::acceptConnection)
        setState(current.copy(confirmed = true))
    }

    private fun rejectCode() {
        peerId?.let(transport::rejectConnection)
        backToSearching(null)
    }

    private fun send(message: ShareMessage) {
        peerId?.let { transport.sendMessage(it, message.toJson()) }
    }

    private fun acceptIncoming() {
        val job = incoming ?: return
        send(ShareMessage.Accept(job.missing))
        if (job.isComplete) finishIncoming(job) else setState(receivingState(job))
    }

    private fun declineIncoming(current: ShareState.IncomingOffer) {
        send(ShareMessage.Decline(DeclineReason.USER))
        incoming = null
        setState(ShareState.Locked(current.peerName, ShareResult.Refused(DeclineReason.USER, byPeer = false)))
    }

    private fun finishIncoming(job: IncomingImport) {
        val result = job.finish()
        send(ShareMessage.Done(result.stored, result.failed))
        incoming = null
        setState(ShareState.Locked(peerName, result))
    }

    private fun receivingState(job: IncomingImport) =
        ShareState.Transferring(peerName, job.manifest, false, job.handled, job.missing.size, job.bytesDone, job.needBytes)

    private fun sendingState(job: OutgoingShare) = ShareState.Transferring(
        peerName, job.offer.manifest, true, job.doneFiles, job.indices.size, job.bytesDone, job.bytesTotal
    )

    private fun handleOffer(manifest: ShareManifest) {
        if (state !is ShareState.Locked) {
            send(ShareMessage.Decline(DeclineReason.BUSY))
            return
        }
        val matches = ShareDiff.match(manifest.tracks, host.localTracks())
        val job = IncomingImport(manifest, matches, host)
        if (job.needBytes > host.freeBytes()) {
            send(ShareMessage.Decline(DeclineReason.NO_SPACE))
            setState(ShareState.Locked(peerName, ShareResult.Refused(DeclineReason.NO_SPACE, byPeer = false)))
        } else {
            incoming = job
            setState(ShareState.IncomingOffer(peerName, manifest, job.missing, job.needBytes))
        }
    }

    private fun handleAccept(indices: List<Int>) {
        val job = outgoing
        val id = peerId
        if (state !is ShareState.Offering || job == null || id == null) return
        val tracks = job.offer.manifest.tracks
        if (indices.any { it !in tracks.indices } || indices.toSet().size != indices.size) {
            dropConnection("The other phone sent invalid data")
            return
        }
        job.indices = indices.sorted()
        setState(sendingState(job))
        for (index in job.indices) {
            transport.sendFile(id, ShareFileSource(index, tracks[index].sizeBytes) { job.offer.open(index) })
        }
    }

    private fun handleMessage(text: String) {
        val message = try {
            ShareMessage.parse(text)
        } catch (ignored: IllegalArgumentException) {
            dropConnection("The other phone sent invalid data")
            return
        }
        when (message) {
            is ShareMessage.Offer -> handleOffer(message.manifest)
            is ShareMessage.Accept -> handleAccept(message.indices)
            is ShareMessage.Decline -> handleDecline(message.reason)
            is ShareMessage.Done -> handleDone(message)
        }
    }

    private fun handleDecline(reason: DeclineReason) {
        if (state is ShareState.Offering) {
            outgoing = null
            setState(ShareState.Locked(peerName, ShareResult.Refused(reason, byPeer = true)))
        }
    }

    private fun handleDone(done: ShareMessage.Done) {
        val job = outgoing
        if (state is ShareState.Transferring && job != null) {
            outgoing = null
            setState(ShareState.Locked(peerName, ShareResult.Sent(job.offer.manifest.name, done.stored, done.failed)))
        }
    }

    /** Receives transport events and forwards each to the session on its executor. */
    private inner class Events : ShareTransport.Listener {
        override fun onPeerFound(peerId: String, peerName: String) = post {
            val theirs = ShareHello.decode(peerName)
            if (state is ShareState.Searching && theirs != null) {
                if (theirs.versionCode != hello.versionCode) {
                    setState(ShareState.Searching("A nearby phone runs a different VibeStation version"))
                } else if (hello.shouldRequestTo(theirs)) {
                    this@ShareSession.peerId = peerId
                    this@ShareSession.peerName = theirs.deviceName
                    transport.requestConnection(peerId)
                    setState(ShareState.Connecting(theirs.deviceName))
                }
            }
        }

        override fun onPeerLost(peerId: String) = post {
            if (state is ShareState.Connecting && this@ShareSession.peerId == peerId) backToSearching(null)
        }

        override fun onConnectionInitiated(peerId: String, peerName: String, code: String) = post {
            val theirs = ShareHello.decode(peerName)
            val free = state is ShareState.Searching || (state is ShareState.Connecting && this@ShareSession.peerId == peerId)
            if (free && theirs != null && theirs.versionCode == hello.versionCode) {
                this@ShareSession.peerId = peerId
                this@ShareSession.peerName = theirs.deviceName
                setState(ShareState.Verifying(theirs.deviceName, code, confirmed = false))
            } else {
                transport.rejectConnection(peerId)
            }
        }

        override fun onConnected(peerId: String) = post {
            if (state is ShareState.Verifying && this@ShareSession.peerId == peerId) {
                setState(ShareState.Locked(peerName))
            }
        }

        override fun onConnectionFailed(peerId: String, reason: String) = post {
            if (this@ShareSession.peerId == peerId) backToSearching(reason)
        }

        override fun onDisconnected(peerId: String) = post {
            if (this@ShareSession.peerId == peerId) {
                val interrupted = state is ShareState.Offering || state is ShareState.IncomingOffer ||
                    state is ShareState.Transferring
                backToSearching(if (interrupted) "Connection lost" else null)
            }
        }

        override fun onMessage(peerId: String, text: String) = post {
            if (this@ShareSession.peerId == peerId) handleMessage(text)
        }

        override fun onFileReceived(peerId: String, index: Int, input: java.io.InputStream) = post {
            val job = activeImport(peerId)
            if (job != null && job.expects(index)) {
                job.receive(index, input)
                if (job.isComplete) finishIncoming(job) else setState(receivingState(job))
            } else {
                runCatching { input.close() }
            }
        }

        override fun onFileProgress(index: Int, bytes: Long, total: Long, sending: Boolean) = post {
            if (state is ShareState.Transferring) {
                val out = outgoing
                val job = incoming
                if (sending && out != null) {
                    out.progress(index, bytes)
                    setState(sendingState(out))
                } else if (!sending && job != null) {
                    job.progress(index, bytes)
                    setState(receivingState(job))
                }
            }
        }

        override fun onFileFailed(index: Int) = post {
            val job = incoming
            if (state !is ShareState.Transferring) return@post
            if (job == null) {
                dropConnection("Transfer failed")
            } else {
                job.fail(index)
                if (job.isComplete) finishIncoming(job) else setState(receivingState(job))
            }
        }
    }
}
