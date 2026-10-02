package com.boogie.vibestation.share

/** One-line descriptions of what Share Mode is doing, for the notification. */
internal object ShareStatus {

    /**
     * Describes [state] briefly. Text from the other phone (its name, the offered item's name) is
     * included as received, since the manifest and hello already length-limit it.
     *
     * @param state Current session state.
     * @return A short sentence, never empty.
     */
    fun text(state: ShareState): String = when (state) {
        is ShareState.Idle, is ShareState.Searching -> "Looking for a nearby phone"
        is ShareState.Connecting -> "Connecting to ${state.peerName}"
        is ShareState.Verifying -> "Check the code with ${state.peerName}"
        is ShareState.Locked -> "Connected to ${state.peerName}"
        is ShareState.Offering -> "Waiting for ${state.peerName} to answer"
        is ShareState.IncomingOffer -> "${state.peerName} wants to share ${state.manifest.name}"
        is ShareState.Transferring -> {
            val verb = if (state.sending) "Sending" else "Receiving"
            "$verb ${state.manifest.name} (${state.doneFiles} of ${state.totalFiles})"
        }
        is ShareState.Closed -> "Share Mode is off"
    }
}
