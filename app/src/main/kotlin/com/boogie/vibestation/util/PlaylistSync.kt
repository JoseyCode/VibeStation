package com.boogie.vibestation.util

import android.content.SharedPreferences
import com.boogie.vibestation.models.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An image read from the device, ready to upload as a playlist cover. */
internal class CoverImage(val bytes: ByteArray, val mimeType: String)

/**
 * The playlist and track deletions made on this device since its last successful sync. The server
 * never infers a deletion from a missing playlist or track, so each one has to be sent explicitly.
 *
 * @param prefs Preferences holding the pending list under [KEY].
 */
internal class PendingDeletions(private val prefs: SharedPreferences) {

    /**
     * Reads the pending deletions in the request format `{ playlists: {name: at}, tracks: [{playlist, t, a, at}] }`.
     *
     * @return The pending deletions; empty when none are stored or the stored text is unreadable.
     */
    fun read(): JSONObject {
        val stored = runCatching { JSONObject(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(JSONObject())
        if (stored.optJSONObject(PLAYLISTS) == null) stored.put(PLAYLISTS, JSONObject())
        if (stored.optJSONArray(TRACKS) == null) stored.put(TRACKS, JSONArray())
        return stored
    }

    /**
     * Records that playlists were deleted or renamed away from these names.
     *
     * @param names Playlist names that no longer exist on this device.
     * @param nowMs Deletion time.
     */
    fun recordPlaylists(names: Collection<String>, nowMs: Long = System.currentTimeMillis()) {
        if (names.isEmpty()) return
        val pending = read()
        names.forEach { pending.getJSONObject(PLAYLISTS).put(it, nowMs) }
        write(pending)
    }

    /**
     * Records that tracks were removed from a playlist.
     *
     * @param playlistName Playlist the tracks were removed from.
     * @param songs Removed tracks.
     * @param nowMs Removal time.
     */
    fun recordTracks(playlistName: String, songs: Collection<Song>, nowMs: Long = System.currentTimeMillis()) {
        if (songs.isEmpty()) return
        val pending = read()
        val tracks = pending.getJSONArray(TRACKS)
        for (song in songs) {
            tracks.put(JSONObject().put("playlist", playlistName).put("t", song.title).put("a", song.artist).put("at", nowMs))
        }
        write(pending)
    }

    /**
     * Drops the deletions that were just delivered, keeping any recorded while the sync was running.
     *
     * @param sent The deletions that were sent, as returned by [read] before the request.
     */
    fun removeSent(sent: JSONObject) {
        val pending = read()
        val sentPlaylists = sent.getJSONObject(PLAYLISTS)
        val pendingPlaylists = pending.getJSONObject(PLAYLISTS)
        sentPlaylists.keys().forEach { name ->
            if (pendingPlaylists.optLong(name) == sentPlaylists.getLong(name)) pendingPlaylists.remove(name)
        }
        val sentTracks = trackEntries(sent.getJSONArray(TRACKS)).toSet()
        val remaining = JSONArray()
        val pendingTracks = pending.getJSONArray(TRACKS)
        for (i in 0 until pendingTracks.length()) {
            if (pendingTracks.get(i).toString() !in sentTracks) remaining.put(pendingTracks.get(i))
        }
        write(pending.put(TRACKS, remaining))
    }

    private fun trackEntries(tracks: JSONArray): List<String> =
        (0 until tracks.length()).map { tracks.get(it).toString() }

    private fun write(pending: JSONObject) {
        prefs.edit().putString(KEY, pending.toString()).apply()
    }

    private companion object {
        const val KEY = "playlists_pending_deleted"
        const val PLAYLISTS = "playlists"
        const val TRACKS = "tracks"
    }
}

/** Pure JSON logic for the playlist sync exchange with the web server; the HTTP calls live in `SyncManager`. */
internal object PlaylistSync {

    // The only names the server issues for covers; anything else from a server is not trusted as a file name
    private val COVER_NAME = Regex("^[a-f0-9]{32}\\.(jpg|png|webp|gif)$")

    /**
     * Whether a cover name is one the server issues, so it is safe to use as a file name.
     *
     * @param name Cover name from a server response.
     * @return True for `<32 hex chars>.<jpg|png|webp|gif>`.
     */
    fun isCoverName(name: String): Boolean = COVER_NAME.matches(name)

    /**
     * Lists the playlists that have a local cover image the server has not stored yet.
     *
     * @param playlists Stored playlists JSON.
     * @return Indexes of playlists with an `imageUri` and no `cover`.
     */
    fun coversNeedingUpload(playlists: JSONArray): List<Int> = (0 until playlists.length()).filter {
        val playlist = playlists.getJSONObject(it)
        playlist.optString("imageUri", "").isNotBlank() && playlist.optString("cover", "").isBlank()
    }

    /**
     * Builds the request body for `POST /api/playlists`.
     *
     * @param playlists Stored playlists JSON, with uploaded covers already named.
     * @param deleted Pending deletions.
     * @return The JSON request body.
     */
    fun buildRequest(playlists: JSONArray, deleted: JSONObject): String =
        JSONObject().put("playlists", playlists).put("deleted", deleted).toString()

    /**
     * Turns the server's merged playlists into the stored format. Names, fields and tracks come from
     * the server; local track ids and cover files are kept where they still apply, and cover images
     * the device does not have yet are downloaded. A playlist the server no longer lists was deleted
     * elsewhere and is dropped.
     *
     * @param sent Playlists as sent in the request.
     * @param server Merged playlists returned by the server.
     * @param coversDir Folder holding downloaded cover files.
     * @param fetchCover Downloads a cover by name into the given file; returns whether it succeeded.
     * @return The playlists to store.
     */
    fun reconcile(sent: JSONArray, server: JSONArray, coversDir: File, fetchCover: (String, File) -> Boolean): JSONArray {
        val local = objects(sent).associateBy { it.optString("name") }
        val result = JSONArray()
        for (remote in objects(server)) {
            val name = remote.optString("name")
            val localPlaylist = local[name]
            val cover = remote.optString("cover", "")
            result.put(
                JSONObject()
                    .put("name", name)
                    .put("imageUri", resolveImageUri(localPlaylist, cover, coversDir, fetchCover))
                    .put("cover", cover)
                    .put("description", remote.optString("description", ""))
                    .put("isFire", remote.optBoolean("isFire", false))
                    .put("lastModified", remote.optLong("lastModified", 0))
                    .put("songData", reconcileTracks(localPlaylist, remote.optJSONArray("songData") ?: JSONArray()))
            )
        }
        return result
    }

    // Keeps this device's own id for tracks it already lists; new tracks keep the server id and are
    // linked to local songs by match key when the playlists are loaded.
    private fun reconcileTracks(localPlaylist: JSONObject?, remoteTracks: JSONArray): JSONArray {
        val localIds = localPlaylist?.optJSONArray("songData")?.let { tracks ->
            objects(tracks).associate { MatchKey.of(it.optString("t"), it.optString("a")) to it.optString("id") }
        }.orEmpty()
        val result = JSONArray()
        for (track in objects(remoteTracks)) {
            val title = track.optString("t")
            val artist = track.optString("a")
            result.put(
                JSONObject()
                    .put("id", localIds[MatchKey.of(title, artist)] ?: track.optString("id"))
                    .put("t", title)
                    .put("a", artist)
                    .put("addedAt", track.optLong("addedAt", 0))
            )
        }
        return result
    }

    private fun resolveImageUri(local: JSONObject?, cover: String, coversDir: File, fetchCover: (String, File) -> Boolean): String {
        val localUri = local?.optString("imageUri", "").orEmpty()
        val localCover = local?.optString("cover", "").orEmpty()
        return when {
            // The upload failed or is still pending: keep the device's own image until it goes through
            cover.isBlank() -> if (localCover.isBlank()) localUri else ""
            localCover == cover && localUri.isNotBlank() -> localUri
            !isCoverName(cover) -> localUri
            else -> {
                val file = File(coversDir, cover)
                if (file.exists() || fetchCover(cover, file)) "file://${file.absolutePath}" else localUri
            }
        }
    }

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
