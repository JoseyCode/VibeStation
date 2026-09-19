package com.boogie.vibestation.models

import java.util.Objects

/**
 * Represents a user-customizable playlist containing a list of Songs.
 * Identity is the playlist name alone.
 */
class Playlist(
    @JvmField var name: String,
    @JvmField var imageUri: String?
) {
    @JvmField
    var description: String = ""

    @JvmField
    var isFire: Boolean = false

    @JvmField
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
