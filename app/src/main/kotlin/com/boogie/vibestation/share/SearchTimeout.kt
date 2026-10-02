package com.boogie.vibestation.share

/** Schedules work for later; the real one wraps a Handler, tests use a manual clock. */
internal fun interface ShareTimer {
    /**
     * Runs [task] once after [delayMs] milliseconds.
     *
     * @param delayMs Delay before the task runs.
     * @param task    Work to run.
     * @return A function that cancels the task if it has not run yet.
     */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * Turns Share Mode off when nobody has shown up for a while, so the radio and the screen lock do not stay
 * on forever. The clock runs only while the session is searching; any other state (a peer, a code, an
 * offer) stops it, and coming back to searching starts a fresh countdown. Changes within searching, such
 * as a new notice, do not restart it.
 *
 * @param delayMs   How long searching may go on without a connection.
 * @param timer     Source of delayed callbacks.
 * @param onTimeout Called once when the time is up.
 */
internal class SearchTimeout(
    private val delayMs: Long,
    private val timer: ShareTimer,
    private val onTimeout: () -> Unit
) {
    private var cancel: (() -> Unit)? = null

    /**
     * Feeds the latest session state.
     *
     * @param state The state the session just entered.
     */
    fun onState(state: ShareState) {
        if (state is ShareState.Searching) {
            if (cancel == null) {
                cancel = timer.schedule(delayMs) {
                    cancel = null
                    onTimeout()
                }
            }
        } else {
            stop()
        }
    }

    /** Cancels a running countdown. */
    fun stop() {
        cancel?.invoke()
        cancel = null
    }
}
