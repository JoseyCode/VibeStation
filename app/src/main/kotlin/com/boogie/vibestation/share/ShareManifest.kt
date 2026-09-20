package com.boogie.vibestation.share

import com.boogie.vibestation.models.Song
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a share offers. The receiver only uses this for wording; every kind is a list of tracks. */
internal enum class ShareKind { SONG, ALBUM, PLAYLIST }

/**
 * One track in a [ShareManifest]. It carries no MediaStore id on purpose: ids are per-device counters,
 * so an id from the sender could point at a different song on the receiver.
 *
 * @property title      Track title.
 * @property artist     Track artist.
 * @property album      Album title.
 * @property durationMs Length in milliseconds, or 0 when unknown; separates live and studio versions.
 * @property sizeBytes  File size in bytes, used for the prompt and the free-space check.
 * @property fileName   Original file name including extension.
 * @property mimeType   MIME type of the audio data.
 */
internal data class ShareTrack(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val fileName: String,
    val mimeType: String
)

/**
 * Description of one share, sent before any audio so the receiver can decide what it needs. Text
 * fields are length-limited when parsed because the peer is untrusted.
 *
 * @property kind        Whether this is a song, an album, or a playlist.
 * @property name        Song, album, or playlist name.
 * @property description Playlist description, empty for other kinds.
 * @property coverBase64 Base64 JPEG cover (playlists only), empty when there is none.
 * @property tracks      Tracks in play order.
 */
internal data class ShareManifest(
    val kind: ShareKind,
    val name: String,
    val description: String,
    val coverBase64: String,
    val tracks: List<ShareTrack>
) {

    /** Total bytes of all tracks, or of only those at [indices] when given. */
    fun totalBytes(indices: Collection<Int>? = null): Long =
        tracks.withIndex().filter { indices == null || it.index in indices }.sumOf { it.value.sizeBytes }

    /**
     * Serializes the manifest to the JSON text sent over the wire.
     *
     * @return JSON object text; [fromJson] reads it back.
     */
    fun toJson(): String {
        val trackArray = JSONArray()
        for (track in tracks) {
            trackArray.put(
                JSONObject()
                    .put("t", track.title)
                    .put("a", track.artist)
                    .put("al", track.album)
                    .put("d", track.durationMs)
                    .put("s", track.sizeBytes)
                    .put("f", track.fileName)
                    .put("m", track.mimeType)
            )
        }
        return JSONObject()
            .put("v", VERSION)
            .put("kind", kind.name)
            .put("name", name)
            .put("description", description)
            .put("cover_b64", coverBase64)
            .put("tracks", trackArray)
            .toString()
    }

    /** Wire format version, size limits, and the parser and builder for manifests. */
    companion object {
        /** Wire format version; a manifest with any other value is rejected. */
        const val VERSION = 1

        /** Most tracks one share may contain. */
        const val MAX_TRACKS = 1000

        /** Longest text kept for a name, title, artist, album, or file name. */
        const val MAX_TEXT = 200

        /** Longest description kept. */
        const val MAX_DESCRIPTION = 1000

        /** Longest cover payload accepted, in Base64 characters. */
        const val MAX_COVER_CHARS = 2_000_000

        /**
         * Parses and validates a manifest from an untrusted peer. Text is truncated to the limits
         * above rather than rejected.
         *
         * @param text JSON text received from the peer.
         * @return The validated manifest.
         * @throws IllegalArgumentException If the text is malformed, has an unsupported version or kind,
         *         no tracks, too many tracks, an oversized cover, or a negative size or duration.
         */
        fun fromJson(text: String): ShareManifest {
            try {
                val root = JSONObject(text)
                require(root.getInt("v") == VERSION) { "Unsupported manifest version" }
                val kind = ShareKind.valueOf(root.getString("kind"))
                val cover = root.optString("cover_b64", "")
                require(cover.length <= MAX_COVER_CHARS) { "Cover too large" }
                val trackArray = root.getJSONArray("tracks")
                require(trackArray.length() in 1..MAX_TRACKS) { "Bad track count" }
                val tracks = (0 until trackArray.length()).map { parseTrack(trackArray.getJSONObject(it)) }
                return ShareManifest(
                    kind,
                    root.getString("name").take(MAX_TEXT),
                    root.optString("description", "").take(MAX_DESCRIPTION),
                    cover,
                    tracks
                )
            } catch (e: JSONException) {
                throw IllegalArgumentException("Malformed manifest", e)
            }
        }

        /**
         * Builds a manifest from local songs. Songs that MediaStore has no readable file for are left
         * out, since they could not be sent.
         *
         * @param kind        What is being shared.
         * @param name        Song, album, or playlist name.
         * @param description Playlist description, or empty.
         * @param coverBase64 Base64 cover, or empty.
         * @param songs       Songs in play order.
         * @param files       File details from [queryShareFiles], keyed by song id.
         * @return The manifest, with [ShareTrack]s only for songs that have file details.
         */
        fun build(
            kind: ShareKind,
            name: String,
            description: String,
            coverBase64: String,
            songs: List<Song>,
            files: Map<String, ShareFile>
        ): ShareManifest {
            val tracks = songs.mapNotNull { song ->
                val file = files[song.id]?.takeIf { it.sizeBytes > 0 } ?: return@mapNotNull null
                ShareTrack(song.title, song.artist, song.album, file.durationMs, file.sizeBytes, file.displayName, file.mimeType)
            }
            return ShareManifest(kind, name, description, coverBase64, tracks)
        }

        private fun parseTrack(json: JSONObject): ShareTrack {
            val duration = json.optLong("d", 0)
            val size = json.getLong("s")
            require(duration >= 0 && size >= 0) { "Negative size or duration" }
            return ShareTrack(
                json.getString("t").take(MAX_TEXT),
                json.getString("a").take(MAX_TEXT),
                json.getString("al").take(MAX_TEXT),
                duration,
                size,
                json.getString("f").take(MAX_TEXT),
                json.optString("m", "").take(MAX_TEXT)
            )
        }
    }
}
