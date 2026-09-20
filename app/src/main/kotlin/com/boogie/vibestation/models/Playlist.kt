package com.boogie.vibestation.models

import java.util.Objects

/**
 * Represents a user-customizable playlist containing a list of Songs.
 * Identity is the playlist name alone.
 *
 * @property name     Display name; also the playlist's identity.
 * @property imageUri Custom cover image URI, or null when none is set.
 */
class Playlist(
    var name: String,
    var imageUri: String?
) {
    /** Free-text description shown with the playlist. */
    var description: String = ""

    /** Whether the user has marked this playlist as a favorite ("fire"). */
    var isFire: Boolean = false

    /** Tracks in playlist order. */
    val songs: ArrayList<Song> = ArrayList()

    /**
     * Checks equality based on unique playlist name.
     *
     * @param other Comparison object.
     * @return True if objects represent the same playlist by name.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Playlist
        return name == other.name
    }

    /** Computes hash code from playlist name. */
    override fun hashCode(): Int = Objects.hash(name)
}
