package com.boogie.vibestation.share

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * A short in-memory log of what the radio is doing, so a debuggable build can show it on the Share screen
 * and two phones can be tested without a cable. Lines also go to logcat.
 */
internal object ShareDebugLog {
    private const val MAX_LINES = 40
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()

    /** Called on the main thread after every new line; set by the screen that shows the log. */
    @Volatile
    var onChange: ((String) -> Unit)? = null

    /**
     * Records one line.
     *
     * @param tag     Logcat tag.
     * @param message What happened.
     */
    fun add(tag: String, message: String) {
        Log.d(tag, message)
        val text = synchronized(lines) {
            lines.addLast(message)
            if (lines.size > MAX_LINES) lines.removeFirst()
            lines.joinToString("\n")
        }
        main.post { onChange?.invoke(text) }
    }
}
