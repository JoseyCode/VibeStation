package com.boogie.vibestation.share

/** Moments of Share Mode worth a tactile tap, whatever the phone's vibration motor can do. */
internal enum class ShareCue {
    /** The verification code appeared. */
    CODE,

    /** The phones are connected. */
    CONNECTED,

    /** The other phone is offering something. */
    OFFER_IN,

    /** One more song finished moving. */
    FILE_DONE,

    /** A share finished. */
    DONE,

    /** An offer was turned down, or could not be taken. */
    REFUSED,

    /** The connection was lost or Share Mode closed by itself. */
    LOST,

    /** A finger touched the screen. */
    PRESS,

    /** The finger went far enough into a zone that letting go will commit. */
    ARM,

    /** The finger backed out of the armed zone. */
    DISARM,

    /** The user committed yes. */
    COMMIT_YES,

    /** The user committed no. */
    COMMIT_NO
}

/** Decides which [ShareCue] a change deserves. Pure: playing the cue is [ShareHaptics]'s job. */
internal object ShareCues {

    /**
     * Cue for the session moving from [old] to [new].
     *
     * @param old The state before.
     * @param new The state after.
     * @return The cue to play, or null for a change nobody needs to feel.
     */
    fun between(old: ShareState, new: ShareState): ShareCue? = when {
        new is ShareState.Verifying && old !is ShareState.Verifying -> ShareCue.CODE
        new is ShareState.Locked && old is ShareState.Verifying -> ShareCue.CONNECTED
        new is ShareState.IncomingOffer && old !is ShareState.IncomingOffer -> ShareCue.OFFER_IN
        new is ShareState.Transferring && old is ShareState.Transferring && new.doneFiles > old.doneFiles -> ShareCue.FILE_DONE
        new is ShareState.Locked -> newResult(old, new)
        new is ShareState.Searching && new.notice != null && old !is ShareState.Searching -> ShareCue.LOST
        new is ShareState.Closed && new.reason != null -> ShareCue.LOST
        else -> null
    }

    /**
     * Cue for the finger changing from [old] to [new].
     *
     * @param old The drag before.
     * @param new The drag after.
     * @return PRESS, ARM or DISARM when one of those just happened, else null.
     */
    fun between(old: GestureState, new: GestureState): ShareCue? = when {
        !old.active && new.active -> ShareCue.PRESS
        old.active && new.active && !old.armed && new.armed -> ShareCue.ARM
        old.active && new.active && old.armed && !new.armed -> ShareCue.DISARM
        else -> null
    }

    /**
     * Cue for lifting the finger.
     *
     * @param outcome What the release meant.
     * @return COMMIT_YES or COMMIT_NO, or null when nothing was committed.
     */
    fun committed(outcome: GestureOutcome): ShareCue? = when (outcome) {
        GestureOutcome.YES -> ShareCue.COMMIT_YES
        GestureOutcome.NO -> ShareCue.COMMIT_NO
        GestureOutcome.NONE -> null
    }

    /**
     * The result of an offer shows up as a new result on the Locked screen. Identity, not equality, tells a new
     * result from the same one delivered again, because two identical refusals in a row are still two events.
     * Declining something yourself is already felt as the commit, so it is not announced a second time.
     */
    private fun newResult(old: ShareState, new: ShareState.Locked): ShareCue? {
        val result = new.lastResult
        val alreadyShown = old is ShareState.Locked && old.lastResult === result
        return when {
            result == null || alreadyShown -> null
            result is ShareResult.Refused && result.reason == DeclineReason.USER && !result.byPeer -> null
            result is ShareResult.Refused -> ShareCue.REFUSED
            else -> ShareCue.DONE
        }
    }
}
