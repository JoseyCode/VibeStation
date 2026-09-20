package com.boogie.vibestation.share

import android.os.Handler

/**
 * [ShareTimer] that runs tasks on a Handler's thread.
 *
 * @param handler Where delayed tasks are posted.
 */
internal class HandlerShareTimer(private val handler: Handler) : ShareTimer {
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}
