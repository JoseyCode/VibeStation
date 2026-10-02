package com.boogie.vibestation.share

import android.util.Log

/** What the radio is doing, written to logcat only; it is never shown on screen. */
internal object ShareDebugLog {

    /**
     * Records one line.
     *
     * @param tag     Logcat tag.
     * @param message What happened.
     */
    fun add(tag: String, message: String) {
        Log.d(tag, message)
    }
}
