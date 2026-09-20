package com.boogie.vibestation.models

import android.net.Uri
import java.util.Objects

/**
 * Represents a single audio track retrieved from the MediaStore database.
 * Identity is the track ID plus storage path; other fields are display metadata.
 *
 * @property id          MediaStore audio row ID, as a string.
 * @property title       Track title.
 * @property artist      Track artist name.
 * @property path        Absolute file path, or null when MediaStore does not report one.
 * @property albumId     MediaStore ID of the album this track belongs to.
 * @property album       Album title.
 * @property trackNumber Track position as reported by the MediaStore TRACK column.
 * @property dateAdded   Time the file was added to MediaStore, in seconds since the epoch.
 */
class Song(
    val id: String,
    val title: String,
    val artist: String,
    val path: String?,
    val albumId: String,
    val album: String,
    val trackNumber: Int,
    val dateAdded: Long
) {

    /** Content provider URI string for this song's album artwork. */
    val albumArtUriString: String
        get() = ALBUM_ART_URI_PREFIX + albumId

    /** Parsed MediaStore URI pointing to this song's album artwork. */
    val albumArtUri: Uri
        get() = Uri.parse(albumArtUriString)

    /**
     * Checks equality based on unique track ID and storage path.
     *
     * @param other Comparison object.
     * @return True if objects represent the same physical track record.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Song
        return id == other.id && path == other.path
    }

    /** Computes hash code from unique track ID and storage path. */
    override fun hashCode(): Int = Objects.hash(id, path)

    /** Shared constants for building artwork URIs. */
    companion object {
        /** Content provider prefix; append an album ID to address that album's artwork. */
        const val ALBUM_ART_URI_PREFIX = "content://media/external/audio/albumart/"
    }
}
