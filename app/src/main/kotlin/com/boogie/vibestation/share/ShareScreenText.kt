package com.boogie.vibestation.share

import java.util.Locale

/**
 * The words the Share screen draws for a state.
 *
 * @property title  Main line, largest.
 * @property detail Second line, smaller; may be empty.
 * @property hint   What dragging does right now; shown while the circle is held.
 */
internal data class ShareScreenText(val title: String, val detail: String, val hint: String) {

    /** Builds the text for a [ShareState]. */
    companion object {
        private const val KB = 1024.0
        private const val PERCENT = 100

        /**
         * Chooses the words for [state]. Names that came from the other phone are inserted as received;
         * they are already length-limited and the screen ellipsizes what does not fit.
         *
         * @param state What Share Mode is doing.
         * @return The text to draw.
         */
        fun of(state: ShareState): ShareScreenText = when (state) {
            is ShareState.Idle, is ShareState.Searching ->
                ShareScreenText(
                    "Looking for a phone",
                    (state as? ShareState.Searching)?.notice ?: "Open Share Mode on the other phone",
                    "drag down to close"
                )
            is ShareState.Connecting -> ShareScreenText("Connecting to ${state.peerName}", "", "drag down to cancel")
            is ShareState.Verifying -> verifying(state)
            is ShareState.Locked -> ShareScreenText(
                "Connected to ${state.peerName}",
                state.lastResult?.let(::describe) ?: "Ready",
                "drag up to send, down to disconnect"
            )
            is ShareState.Offering ->
                ShareScreenText("Offering ${state.manifest.name}", "Waiting for ${state.peerName}", "drag down to cancel")
            is ShareState.IncomingOffer -> ShareScreenText(
                "Accept ${label(state.manifest.kind)} ${state.manifest.name}?",
                "${state.missing.size} new of ${state.manifest.tracks.size}, ${megabytes(state.needBytes)}",
                "drag up to accept, down to decline"
            )
            is ShareState.Transferring -> transferring(state)
            is ShareState.Closed -> ShareScreenText("Share Mode closed", state.reason ?: "", "")
        }

        /**
         * How far a transfer has got, for a progress ring.
         *
         * @param state Current state.
         * @return 0..1 while transferring, otherwise null.
         */
        fun progress(state: ShareState): Float? {
            if (state !is ShareState.Transferring || state.bytesTotal <= 0) return null
            return (state.bytesDone.toFloat() / state.bytesTotal).coerceIn(0f, 1f)
        }

        private fun verifying(state: ShareState.Verifying) = if (state.confirmed) {
            ShareScreenText("Code ${state.code}", "Waiting for ${state.peerName} to confirm", "drag down to cancel")
        } else {
            ShareScreenText("Code ${state.code}", "Same code on ${state.peerName}'s phone?", "drag up if it matches, down if not")
        }

        private fun transferring(state: ShareState.Transferring): ShareScreenText {
            val verb = if (state.sending) "Sending" else "Receiving"
            val percent = progress(state)?.let { " (${(it * PERCENT).toInt()}%)" } ?: ""
            return ShareScreenText(
                "$verb ${state.manifest.name}",
                "${state.doneFiles} of ${state.totalFiles} songs$percent",
                "drag down to cancel"
            )
        }

        private fun describe(result: ShareResult): String = when (result) {
            is ShareResult.Sent -> "Sent ${result.name}: ${stored(result.stored, result.failed)}"
            is ShareResult.Received -> "Got ${result.name}: ${stored(result.stored, result.failed)}"
            is ShareResult.Refused -> refusal(result)
        }

        private fun stored(stored: Int, failed: Int) =
            if (failed == 0) "$stored new" else "$stored new, $failed failed"

        private fun refusal(result: ShareResult.Refused): String {
            val who = if (result.byPeer) "They" else "You"
            return when (result.reason) {
                DeclineReason.USER -> "$who declined"
                DeclineReason.BUSY -> "They were busy"
                DeclineReason.NO_SPACE -> if (result.byPeer) "They ran out of space" else "Not enough space"
            }
        }

        private fun label(kind: ShareKind) = when (kind) {
            ShareKind.SONG -> "song"
            ShareKind.ALBUM -> "album"
            ShareKind.PLAYLIST -> "playlist"
        }

        private fun megabytes(bytes: Long) = String.format(Locale.getDefault(), "%.1f MB", bytes / KB / KB)
    }
}
