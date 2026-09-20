package com.boogie.vibestation.models

import java.util.Objects

/**
 * Represents a grouped collection of Songs under a unique album.
 * Identity is the album ID alone.
 */
class Album(
    val albumId: String,
    val name: String,
    val artist: String,
    val dateAdded: Long
) {
    var isFire: Boolean = false

    val songs: ArrayList<Song> = ArrayList()

    /**
     * Checks equality based on unique album identifier.
     *
     * @param other Comparison object.
     * @return True if objects represent the same album record.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Album
        return albumId == other.albumId
    }

    /** Computes hash code from unique album identifier. */
    override fun hashCode(): Int = Objects.hash(albumId)
}
