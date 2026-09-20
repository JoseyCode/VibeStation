package com.boogie.vibestation.models

import android.net.Uri
import java.util.Objects

/**
 * Represents a single audio track retrieved from the MediaStore database.
 * Identity is the track ID plus storage path; other fields are display metadata.
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

    companion object {
        const val ALBUM_ART_URI_PREFIX = "content://media/external/audio/albumart/"
    }
}
