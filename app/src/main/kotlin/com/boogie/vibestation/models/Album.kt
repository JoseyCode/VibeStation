package com.boogie.vibestation.models

import java.util.Objects

/**
 * Represents a grouped collection of Songs under a unique album.
 * Identity is the album ID alone.
 *
 * @property albumId   MediaStore album ID.
 * @property name      Album title.
 * @property artist    Artist of the album's first scanned song.
 * @property dateAdded Date added of the album's first scanned song, in seconds since the epoch.
 */
class Album(
    val albumId: String,
    val name: String,
    val artist: String,
    val dateAdded: Long
) {
    /** Whether the user has marked this album as a favorite ("fire"). */
    var isFire: Boolean = false

    /** Tracks belonging to this album. */
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
