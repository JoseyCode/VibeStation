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

    /** Name of this playlist's cover as stored on the sync server; blank until the cover has been uploaded. */
    var cover: String = ""

    /**
     * Epoch milliseconds of the last change to the playlist itself (name, description, fire flag,
     * cover). Adding or removing tracks does not move it, so a track edit made offline can never
     * revert another device's cover or description change.
     */
    var lastModified: Long = 0

    /** Epoch milliseconds each track was added, keyed by match key; 0 for tracks added before tracking began. */
    val addedAt: HashMap<String, Long> = HashMap()

    /** Marks the playlist itself as changed now. */
    fun touch(nowMs: Long = System.currentTimeMillis()) {
        lastModified = nowMs
    }

    /**
     * Name this playlist had on the server before an unsynced rename, so the server can move tracks
     * other devices added under the old name; null once the rename has synced.
     */
    var renamedFrom: String? = null

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
