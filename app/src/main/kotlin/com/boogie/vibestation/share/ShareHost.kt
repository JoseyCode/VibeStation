package com.boogie.vibestation.share

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/**
 * A prepared share on the sending side.
 *
 * @property manifest What is offered.
 * @property open     Opens the bytes of the track at the given manifest index.
 */
internal class ShareOffer(val manifest: ShareManifest, val open: (index: Int) -> InputStream)

/**
 * What the session needs from the app: the music library, storage, and the playlist store. The Android
 * implementation talks to MediaStore and SharedPreferences; tests use a fake. Methods run on the
 * session's worker thread, so blocking I/O is fine.
 */
internal interface ShareHost {

    /** Songs in this device's library with their durations, used to decide what an offer adds. */
    fun localTracks(): List<LocalTrack>

    /** Free storage in bytes available for new music. */
    fun freeBytes(): Long

    /**
     * Writes a received track into the library and returns the song as it now exists on this device.
     *
     * @param track Manifest entry describing the file.
     * @param input The file's bytes; the caller closes it.
     * @return The new song, identified by this device's id.
     * @throws IOException If the track could not be stored; implementations must wrap other failures.
     */
    @Throws(IOException::class)
    fun storeTrack(track: ShareTrack, input: InputStream): LocalTrack

    /** Names of the playlists on this device. */
    fun existingPlaylistNames(): List<String>

    /**
     * Stores a received playlist cover.
     *
     * @param encoded Base64 cover from the manifest, possibly empty.
     * @return URI of the stored cover, or null if there is none or it could not be stored.
     */
    fun storeCover(encoded: String): String?

    /**
     * Adds a playlist to the stored playlists.
     *
     * @param entry Playlist in the stored format, with this device's song ids.
     * @throws IOException If the playlists could not be updated.
     */
    @Throws(IOException::class)
    fun addPlaylist(entry: JSONObject)
}
