package com.boogie.vibestation.share

/** How the last offer on the current connection ended, shown once the phones are idle again. */
internal sealed interface ShareResult {
    /** This phone sent [name]; the peer stored [stored] tracks and could not store [failed]. */
    data class Sent(val name: String, val stored: Int, val failed: Int) : ShareResult

    /**
     * This phone received [name]: [stored] new tracks were written and [failed] could not be.
     * [playlistName] is the playlist that was created, or null if none was (not a playlist, or saving failed).
     */
    data class Received(val name: String, val stored: Int, val failed: Int, val playlistName: String?) : ShareResult

    /** An offer was refused for [reason]; [byPeer] tells whether the other phone refused or this one did. */
    data class Refused(val reason: DeclineReason, val byPeer: Boolean) : ShareResult
}

/** What Share Mode is doing right now. The screen is a pure function of this. */
internal sealed interface ShareState {
    /** Not started. */
    data object Idle : ShareState

    /** Advertising and discovering; [notice] explains something the user should know, such as a version mismatch. */
    data class Searching(val notice: String? = null) : ShareState

    /** A connection request to [peerName] is in flight. */
    data class Connecting(val peerName: String) : ShareState

    /** Both phones show [code]; waiting for the user to confirm it ([confirmed] once this phone did). */
    data class Verifying(val peerName: String, val code: String, val confirmed: Boolean) : ShareState

    /** Connected and idle; either phone may start a send. [lastResult] is the outcome of the previous offer. */
    data class Locked(val peerName: String, val lastResult: ShareResult? = null) : ShareState

    /** This phone offered [manifest] and waits for the answer. */
    data class Offering(val peerName: String, val manifest: ShareManifest) : ShareState

    /** The peer offered [manifest]; [missing] are the track indices this phone lacks, needing [needBytes]. */
    data class IncomingOffer(
        val peerName: String,
        val manifest: ShareManifest,
        val missing: List<Int>,
        val needBytes: Long
    ) : ShareState

    /** Files are moving: [sending] tells the direction; [doneFiles] of [totalFiles] and [bytesDone] of [bytesTotal]. */
    data class Transferring(
        val peerName: String,
        val manifest: ShareManifest,
        val sending: Boolean,
        val doneFiles: Int,
        val totalFiles: Int,
        val bytesDone: Long,
        val bytesTotal: Long
    ) : ShareState

    /** Share Mode was left; [reason] is set when it was not the user's choice. */
    data class Closed(val reason: String? = null) : ShareState
}

/** Whether dragging up currently means something on this screen. */
internal val ShareState.allowsYes: Boolean
    get() = when (this) {
        is ShareState.Verifying -> !confirmed
        is ShareState.Locked, is ShareState.IncomingOffer -> true
        else -> false
    }

/** Whether dragging down currently means something on this screen. */
internal val ShareState.allowsNo: Boolean
    get() = this !is ShareState.Idle && this !is ShareState.Closed
