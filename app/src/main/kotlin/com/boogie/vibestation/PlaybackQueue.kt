package com.boogie.vibestation

import com.boogie.vibestation.models.Song

/**
 * Ordered list of songs with a cursor that wraps at both ends. Pure state and arithmetic, kept out of
 * [AudioService] so queue navigation can be reasoned about and tested without a MediaPlayer.
 */
internal class PlaybackQueue {

    // Held by reference so edits the caller makes to its list stay visible here
    private var songs: List<Song> = emptyList()
    private var index = -1

    /** True when no songs are queued. */
    val isEmpty: Boolean
        get() = songs.isEmpty()

    /** Song under the cursor, or null when the queue is empty or the cursor is outside it. */
    val current: Song?
        get() = songs.getOrNull(index)

    /**
     * Replaces the queue and places the cursor.
     *
     * @param newQueue Songs in play order.
     * @param position Index of the song to make current; an out-of-range value leaves no current song.
     */
    fun set(newQueue: List<Song>, position: Int) {
        songs = newQueue
        index = position
    }

    /**
     * Moves the cursor forward one song, wrapping from the last song to the first.
     *
     * @return False, without moving, if the queue is empty.
     */
    fun advance(): Boolean {
        if (songs.isEmpty()) return false
        index = (index + 1) % songs.size
        return true
    }

    /**
     * Moves the cursor back one song, wrapping from the first song to the last.
     *
     * @return False, without moving, if the queue is empty.
     */
    fun retreat(): Boolean {
        if (songs.isEmpty()) return false
        index = (index - 1 + songs.size) % songs.size
        return true
    }

    /**
     * Songs that follow the cursor, wrapping around the end of the queue (used for artwork preloading).
     *
     * @param count How many upcoming songs to return; zero or negative yields none.
     */
    fun upcoming(count: Int): List<Song> {
        if (songs.isEmpty()) return emptyList()
        return (1..count).map { songs[(index + it) % songs.size] }
    }
}
