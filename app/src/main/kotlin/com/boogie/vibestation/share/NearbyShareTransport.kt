package com.boogie.vibestation.share

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * [ShareTransport] over Google Nearby Connections. Only adapts the Nearby callbacks to the interface;
 * the byte-level rules live in [ShareFraming], [FileRouter] and [ReceivedFiles], which are unit tested.
 * This class needs a real radio and Play Services, so it is checked on two phones rather than in unit tests.
 *
 * All state is touched only on [serial], because Nearby delivers callbacks on the main thread while file
 * copies run on [io]; results hop back to [serial] before they change anything.
 *
 * @param context Any context; the application context is what Nearby keeps.
 * @param cacheDir Private directory for incoming files that are still arriving.
 * @param serial   Runs every state change one at a time, in order.
 * @param io       Runs the blocking copy of an incoming file to disk.
 */
@Suppress("TooManyFunctions") // one small method per Nearby callback keeps each of them easy to read
internal class NearbyShareTransport(
    context: Context,
    private val cacheDir: File,
    private val serial: Executor,
    private val io: Executor
) : ShareTransport {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val router = FileRouter()
    private val assemblers = HashMap<String, FrameAssembler>()
    private val copies = HashMap<Long, File>()
    private val streamsInFlight = HashSet<Long>()
    private val sendQueue = ArrayDeque<Pair<String, ShareFileSource>>()
    private var listener: ShareTransport.Listener? = null
    private var hello = ""
    private var running = false
    private var peer: String? = null
    private var sendingPayload: Long? = null

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) = serial.execute {
            // One phone at a time: stop being visible while this connection is being set up.
            peer = endpointId
            client.stopAdvertising()
            client.stopDiscovery()
            listener?.onConnectionInitiated(endpointId, info.endpointName, info.authenticationDigits)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) = serial.execute {
            if (result.status.isSuccess) {
                listener?.onConnected(endpointId)
            } else {
                connectionEnded()
                listener?.onConnectionFailed(endpointId, result.status.statusMessage ?: "Connection failed")
            }
        }

        override fun onDisconnected(endpointId: String) = serial.execute {
            connectionEnded()
            listener?.onDisconnected(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) = serial.execute {
            listener?.onPeerFound(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) = serial.execute {
            listener?.onPeerLost(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) = serial.execute {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { onBytes(endpointId, it) }
                Payload.Type.STREAM -> onStream(payload)
                else -> client.cancelPayload(payload.id) // we never ask for FILE payloads; they would land in Downloads
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = serial.execute {
            onTransferUpdate(update)
        }
    }

    override fun start(hello: String, listener: ShareTransport.Listener) {
        serial.execute {
            this.hello = hello
            this.listener = listener
            running = true
            deleteStaleCopies()
            startRadio()
        }
    }

    override fun stop() {
        serial.execute {
            running = false
            client.stopAllEndpoints()
            connectionEnded()
            listener = null
        }
    }

    override fun requestConnection(peerId: String) {
        client.requestConnection(hello, peerId, lifecycle).addOnFailureListener { error ->
            serial.execute { listener?.onConnectionFailed(peerId, error.message ?: "Could not connect") }
        }
    }

    override fun acceptConnection(peerId: String) {
        client.acceptConnection(peerId, payloads)
    }

    override fun rejectConnection(peerId: String) {
        client.rejectConnection(peerId)
    }

    override fun disconnect(peerId: String) {
        serial.execute {
            client.disconnectFromEndpoint(peerId)
            connectionEnded()
        }
    }

    override fun sendMessage(peerId: String, text: String) {
        serial.execute {
            ShareFraming.messageFrames(text).forEach { client.sendPayload(peerId, Payload.fromBytes(it)) }
        }
    }

    override fun sendFile(peerId: String, source: ShareFileSource) {
        serial.execute {
            sendQueue.addLast(peerId to source)
            sendNext()
        }
    }

    /** Starts advertising and discovering; failures such as "already advertising" are harmless and ignored. */
    private fun startRadio() {
        val strategy = Strategy.P2P_POINT_TO_POINT
        client.startAdvertising(hello, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    /** Forgets the connection and its half-finished files, then becomes visible again if Share Mode is still on. */
    private fun connectionEnded() {
        peer = null
        assemblers.clear()
        router.clear()
        streamsInFlight.clear()
        sendQueue.clear()
        sendingPayload?.let(client::cancelPayload)
        sendingPayload = null
        copies.values.forEach { it.delete() }
        copies.clear()
        if (running) startRadio()
    }

    /** Decodes a bytes payload: a slice of a control message, or the header naming a file payload. */
    private fun onBytes(peerId: String, bytes: ByteArray) {
        val ok = try {
            when (val frame = ShareFraming.parse(bytes)) {
                is ShareFrame.Chunk -> assemblers.getOrPut(peerId) { FrameAssembler() }.add(frame)?.let {
                    listener?.onMessage(peerId, it)
                }
                is ShareFrame.FileHeader -> handle(router.announce(frame.index, frame.payloadId))
            }
            true
        } catch (ignored: IllegalArgumentException) {
            false
        }
        if (!ok) dropMisbehavingPeer(peerId)
    }

    /** A peer that sends malformed frames is not one of ours; hang up. */
    private fun dropMisbehavingPeer(peerId: String) {
        client.disconnectFromEndpoint(peerId)
        connectionEnded()
        listener?.onDisconnected(peerId)
    }

    /** Copies an incoming stream to a temp file as it arrives, so the radio is never left waiting on us. */
    private fun onStream(payload: Payload) {
        val stream = payload.asStream()?.asInputStream()
        if (stream == null || streamsInFlight.size >= MAX_STREAMS_IN_FLIGHT) {
            client.cancelPayload(payload.id)
            return
        }
        val id = payload.id
        streamsInFlight += id
        io.execute {
            val file = try {
                stream.use { ReceivedFiles.drain(it, cacheDir, MAX_FILE_BYTES) }
            } catch (ignored: IOException) {
                null
            }
            serial.execute { copyFinished(id, file) }
        }
    }

    private fun copyFinished(id: Long, file: File?) {
        if (id !in streamsInFlight) {
            file?.delete()
            return
        }
        file?.let { copies[id] = it }
        handle(router.copyFinished(id, file != null))
        if (file == null) streamsInFlight -= id
    }

    private fun onTransferUpdate(update: PayloadTransferUpdate) {
        val id = update.payloadId
        val status = when (update.status) {
            PayloadTransferUpdate.Status.SUCCESS -> TransferStatus.SUCCESS
            PayloadTransferUpdate.Status.IN_PROGRESS -> TransferStatus.IN_PROGRESS
            else -> TransferStatus.FAILED
        }
        if (id == sendingPayload) {
            handle(router.outgoingUpdate(id, status, update.bytesTransferred, update.totalBytes))
            if (status != TransferStatus.IN_PROGRESS) {
                sendingPayload = null
                sendNext()
            }
        } else if (id in streamsInFlight) {
            if (status == TransferStatus.FAILED) copies.remove(id)?.delete()
            handle(router.incomingUpdate(id, status, update.bytesTransferred, update.totalBytes))
            if (status == TransferStatus.FAILED) streamsInFlight -= id
        }
    }

    /** Sends the next queued file if none is in flight; the header goes first so the receiver can name the payload. */
    private fun sendNext() {
        if (sendingPayload != null) return
        val (peerId, source) = sendQueue.removeFirstOrNull() ?: return
        val stream = try {
            source.open()
        } catch (ignored: IOException) {
            null
        }
        if (stream == null) {
            listener?.onFileFailed(source.index)
            sendNext()
        } else {
            val payload = Payload.fromStream(stream)
            sendingPayload = payload.id
            router.sending(source.index, payload.id)
            client.sendPayload(peerId, Payload.fromBytes(ShareFraming.fileHeader(source.index, payload.id)))
            client.sendPayload(peerId, payload)
        }
    }

    private fun handle(events: List<FileEvent>) {
        for (event in events) {
            when (event) {
                is FileEvent.Progress -> listener?.onFileProgress(event.index, event.bytes, event.total, event.sending)
                is FileEvent.Failed -> listener?.onFileFailed(event.index)
                is FileEvent.Received -> deliver(event)
            }
        }
    }

    private fun deliver(event: FileEvent.Received) {
        streamsInFlight -= event.payloadId
        val file = copies.remove(event.payloadId)
        val peerId = peer
        if (file == null || peerId == null) {
            file?.delete()
            listener?.onFileFailed(event.index)
            return
        }
        try {
            listener?.onFileReceived(peerId, event.index, ReceivedFiles.openAndDelete(file))
        } catch (ignored: IOException) {
            listener?.onFileFailed(event.index)
        }
    }

    /** Removes files left behind if the app was killed mid-transfer. */
    private fun deleteStaleCopies() {
        for (file in cacheDir.listFiles().orEmpty()) {
            if (file.name.startsWith(ReceivedFiles.PREFIX) && file.name.endsWith(ReceivedFiles.SUFFIX)) file.delete()
        }
    }

    private companion object {
        const val SERVICE_ID = "com.boogie.vibestation.share"

        /** Files arrive one at a time, so a few in flight at once already means the peer is misbehaving. */
        const val MAX_STREAMS_IN_FLIGHT = 3

        /** Largest single audio file accepted from a peer. */
        const val MAX_FILE_BYTES = 1L shl 30
    }
}
