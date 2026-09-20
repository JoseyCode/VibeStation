package com.boogie.vibestation.util

import java.util.Locale

/**
 * Pure text formatters for values shown in the player UI. Digits follow the default [Locale], so
 * the output matches the surrounding system UI.
 */
object FormatUtil {

    private const val MS_PER_SECOND = 1000
    private const val SECONDS_PER_MINUTE = 60
    private const val MINUTES_PER_HOUR = 60

    /**
     * Converts a millisecond position to a clock string: "M:SS" under an hour, "H:MM:SS" from an
     * hour up. Minutes are not zero-padded in the short form, and there is no day rollover.
     *
     * @param positionMs Position in milliseconds; expected to be non-negative.
     * @return Formatted time string.
     */
    fun formatTime(positionMs: Int): String {
        val totalSeconds = positionMs / MS_PER_SECOND
        val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats a playback speed multiplier for button and dialog labels, always with two decimals.
     *
     * @param speed Speed multiplier, e.g. 1.25f.
     * @return Label such as "1.25x".
     */
    fun formatSpeed(speed: Float): String = String.format(Locale.getDefault(), "%.2fx", speed)
}
