package com.boogie.vibestation.share

import com.boogie.vibestation.SyncManager
import com.boogie.vibestation.util.NewTrack
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a received [ShareManifest] into things this device can store: where and under what name each
 * file is written, and a playlist entry whose songs are identified by THIS device's MediaStore ids.
 * Everything taken from the peer (names, paths) is treated as untrusted.
 */
internal object ShareImport {

    private const val MAX_SEGMENT = 100
    private const val DEFAULT_PLAYLIST_NAME = "Shared playlist"
    private const val DEFAULT_MIME = "audio/mpeg"

    // Extension to MIME type for the audio formats MediaStore accepts; also used in reverse for names.
    private val MIME_BY_EXTENSION = linkedMapOf(
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/x-wav"
    )

    /**
     * Picks a playlist name that does not clash with an existing one by appending " (2)", " (3)", and so on.
     *
     * @param name     Name from the manifest; blank falls back to "Shared playlist".
     * @param existing Names of the playlists already on this device.
     * @return [name] if free, otherwise the first free numbered variant.
     */
    fun uniqueName(name: String, existing: Collection<String>): String {
        val base = name.trim().ifEmpty { DEFAULT_PLAYLIST_NAME }
        if (base !in existing) return base
        var n = 2
        while ("$base ($n)" in existing) n++
        return "$base ($n)"
    }

    /**
     * Builds the folder a received track is stored in, `Music/<Artist>/<Album>/`. Untagged files rely on
     * this layout for their artist and album (see `MusicLibraryUtil.buildSongFromCursor`). Path
     * separators, dot segments and control characters from the peer are neutralized so a track can
     * never be written outside `Music/`.
     *
     * @param artist Artist from the manifest.
     * @param album  Album from the manifest.
     * @return A relative path ending in "/".
     */
    fun relativePath(artist: String, album: String): String =
        "Music/${cleanSegment(artist, "Unknown Artist")}/${cleanSegment(album, "Unknown Album")}/"

    /**
     * Chooses the file name a received track is stored under: the sender's name made safe, or
     * "<title>.<ext>" when that name is unusable.
     *
     * @param track Track from the manifest.
     * @return A non-empty file name without path separators.
     */
    fun displayName(track: ShareTrack): String {
        val fromSender = cleanSegment(track.fileName, "")
        if (fromSender.isNotEmpty()) return fromSender
        val extension = MIME_BY_EXTENSION.entries.firstOrNull { it.value == track.mimeType }?.key ?: "mp3"
        return "${cleanSegment(track.title, "track")}.$extension"
    }

    /**
     * Describes where and how to store a received track in MediaStore. MediaStore refuses non-audio MIME
     * types, so anything the peer sends that does not start with "audio/" is replaced by a guess from the extension.
     *
     * @param track Track from the manifest.
     * @return Metadata for [com.boogie.vibestation.util.MediaStoreWriter.insertTrack].
     */
    fun newTrack(track: ShareTrack): NewTrack {
        val name = displayName(track)
        val mime = track.mimeType.takeIf { it.startsWith("audio/") }
            ?: MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]
            ?: DEFAULT_MIME
        return NewTrack(name, track.title, track.artist, track.album, mime, relativePath(track.artist, track.album))
    }

    /**
     * Builds the playlist JSON object in the format `PlaylistUtil.savePlaylists` stores. Songs are written
     * with the ids from [resolved], which are this device's, never the sender's; tracks that could not
     * be matched or stored are skipped.
     *
     * @param manifest Received manifest, supplying the description.
     * @param name     Final playlist name, already made unique.
     * @param imageUri Local cover URI, or null for none.
     * @param resolved For each manifest track in order, the local song it maps to, or null if unavailable.
     * @return The playlist entry to add to the stored playlists.
     */
    fun playlistEntry(manifest: ShareManifest, name: String, imageUri: String?, resolved: List<LocalTrack?>): JSONObject {
        require(resolved.size == manifest.tracks.size) { "resolved must have one entry per manifest track" }
        val songs = JSONArray()
        for (local in resolved.filterNotNull()) {
            songs.put(JSONObject().put("id", local.songId).put("t", local.title).put("a", local.artist))
        }
        return JSONObject()
            .put("name", name)
            .put("imageUri", imageUri ?: "")
            .put("description", manifest.description)
            .put("isFire", false)
            .put("songData", songs)
    }

    /**
     * Lists the playlist names in the stored playlists JSON.
     *
     * @param playlistsJson Stored JSON array text.
     * @return Names in stored order.
     * @throws org.json.JSONException If the text is not a JSON array.
     */
    fun playlistNames(playlistsJson: String): List<String> {
        val array = JSONArray(playlistsJson)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
    }

    /**
     * Adds [entry] to the end of the stored playlists JSON. Corrupt stored JSON is not replaced; the
     * exception propagates so the user's playlists are never overwritten by a share.
     *
     * @param playlistsJson Stored JSON array text.
     * @param entry         Playlist to add.
     * @return The updated JSON array text.
     * @throws org.json.JSONException If [playlistsJson] is not a JSON array.
     */
    fun appendPlaylist(playlistsJson: String, entry: JSONObject): String = JSONArray(playlistsJson).put(entry).toString()

    /** Makes [raw] usable as one path segment or file name, or returns [fallback] if nothing usable is left. */
    private fun cleanSegment(raw: String, fallback: String): String {
        if (raw.isBlank()) return fallback
        val cleaned = SyncManager.safeFileName(raw).trim().trimStart('.').take(MAX_SEGMENT).trimEnd('.', ' ')
        return cleaned.ifEmpty { fallback }
    }
}
