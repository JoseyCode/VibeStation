package com.boogie.vibestation.share

/** Progress of one file payload as reported by the radio. */
internal enum class TransferStatus { IN_PROGRESS, SUCCESS, FAILED }

/** What the router concluded about a file transfer. */
internal sealed interface FileEvent {
    /** [bytes] of [total] moved for track [index]. */
    data class Progress(val index: Int, val bytes: Long, val total: Long, val sending: Boolean) : FileEvent

    /** Track [index] arrived completely in payload [payloadId]. */
    data class Received(val index: Int, val payloadId: Long) : FileEvent

    /** The transfer of track [index] failed. */
    data class Failed(val index: Int, val sending: Boolean) : FileEvent
}

/**
 * Ties anonymous file payloads to manifest track indices. A received file is only usable once three things
 * have all happened, in any order: its header named it, the radio reported success, and the local copy of
 * its bytes finished. Sending is simpler: an index is remembered per payload id so progress can be labelled.
 * Pure bookkeeping, so the ordering cases can be tested without a radio.
 */
internal class FileRouter {

    private class Slot {
        var index: Int? = null
        var success = false
        var copied = false
        var failed = false
        var bytes = 0L
        var total = 0L
    }

    private val incoming = HashMap<Long, Slot>()
    private val outgoing = HashMap<Long, Int>()

    /**
     * Records the header for an incoming file.
     *
     * @param index     Track position in the manifest.
     * @param payloadId Nearby payload id of the file.
     * @return Events that became due now.
     */
    fun announce(index: Int, payloadId: Long): List<FileEvent> {
        val slot = slotFor(payloadId) ?: return emptyList()
        slot.index = index
        return flush(payloadId, slot)
    }

    /**
     * Records a transfer update for an incoming file.
     *
     * @param payloadId Nearby payload id.
     * @param status    Where the transfer stands.
     * @param bytes     Bytes received so far.
     * @param total     Total bytes, or a non-positive value if unknown.
     * @return Events that became due now.
     */
    fun incomingUpdate(payloadId: Long, status: TransferStatus, bytes: Long, total: Long): List<FileEvent> {
        val slot = slotFor(payloadId) ?: return emptyList()
        slot.bytes = bytes
        slot.total = total
        when (status) {
            TransferStatus.SUCCESS -> slot.success = true
            TransferStatus.FAILED -> slot.failed = true
            TransferStatus.IN_PROGRESS -> Unit
        }
        return flush(payloadId, slot)
    }

    /**
     * Records that the local copy of an incoming file's bytes finished.
     *
     * @param payloadId Nearby payload id.
     * @param ok        False if copying failed.
     * @return Events that became due now.
     */
    fun copyFinished(payloadId: Long, ok: Boolean): List<FileEvent> {
        val slot = slotFor(payloadId) ?: return emptyList()
        if (ok) slot.copied = true else slot.failed = true
        return flush(payloadId, slot)
    }

    /** Remembers that payload [payloadId] carries the track at [index] being sent. */
    fun sending(index: Int, payloadId: Long) {
        outgoing[payloadId] = index
    }

    /**
     * Records a transfer update for a file being sent.
     *
     * @param payloadId Nearby payload id.
     * @param status    Where the transfer stands.
     * @param bytes     Bytes sent so far.
     * @param total     Total bytes, or a non-positive value if unknown.
     * @return Progress while running, a failure if it failed, and nothing for unknown payloads.
     */
    fun outgoingUpdate(payloadId: Long, status: TransferStatus, bytes: Long, total: Long): List<FileEvent> {
        val index = outgoing[payloadId] ?: return emptyList()
        return when (status) {
            TransferStatus.IN_PROGRESS -> listOf(FileEvent.Progress(index, bytes, total, sending = true))
            TransferStatus.SUCCESS -> {
                outgoing.remove(payloadId)
                listOf(FileEvent.Progress(index, bytes, total, sending = true))
            }
            TransferStatus.FAILED -> {
                outgoing.remove(payloadId)
                listOf(FileEvent.Failed(index, sending = true))
            }
        }
    }

    /** Forgets everything, for example when the connection ends. */
    fun clear() {
        incoming.clear()
        outgoing.clear()
    }

    /** A slot for [payloadId], or null when too many unknown payloads are being tracked (a flooding peer). */
    private fun slotFor(payloadId: Long): Slot? {
        if (payloadId !in incoming && incoming.size >= MAX_SLOTS) return null
        return incoming.getOrPut(payloadId) { Slot() }
    }

    private fun flush(payloadId: Long, slot: Slot): List<FileEvent> {
        val index = slot.index ?: return emptyList()
        val events = mutableListOf<FileEvent>()
        if (slot.failed) {
            incoming.remove(payloadId)
            events += FileEvent.Failed(index, sending = false)
        } else {
            events += FileEvent.Progress(index, slot.bytes, slot.total, sending = false)
            if (slot.success && slot.copied) {
                incoming.remove(payloadId)
                events += FileEvent.Received(index, payloadId)
            }
        }
        return events
    }

    private companion object {
        const val MAX_SLOTS = 2000
    }
}
