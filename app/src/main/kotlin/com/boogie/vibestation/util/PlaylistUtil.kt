package com.boogie.vibestation.util

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Handles serialization, persistence, backup export, and restoration for playlists.
 */
object PlaylistUtil {

    private const val PLAYLISTS_KEY = "playlists"

    private val backupExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Builds the fallback key that links a saved playlist entry to a library song when its ID no
     * longer matches (for example after the file was re-scanned). [parsePlaylists] looks entries up
     * with this key, so the caller must build its `songNameMap` with it too.
     *
     * @param title  Song title.
     * @param artist Song artist.
     * @return Lowercase "title_artist" key; no trimming or punctuation stripping is applied.
     */
    fun songNameKey(title: String, artist: String): String = "${title}_$artist".lowercase(Locale.getDefault())

    /**
     * Moves the playlist shown at [from] to position [to] in the displayed (possibly filtered) list
     * and mirrors the move into the master list, so a manual order survives clearing the search.
     * If either playlist is missing from [all], only the displayed list changes.
     *
     * @param all     Master playlist list, in persisted order.
     * @param display Displayed list, a filtered view of [all].
     * @param from    Displayed index of the dragged playlist.
     * @param to      Displayed index it was dropped on.
     */
    fun movePlaylist(all: MutableList<Playlist>, display: MutableList<Playlist>, from: Int, to: Int) {
        val allFrom = all.indexOf(display[from])
        val allTo = all.indexOf(display[to])
        if (allFrom != -1 && allTo != -1) {
            all.add(allTo, all.removeAt(allFrom))
        }
        display.add(to, display.removeAt(from))
    }

    /**
     * Serializes playlist collection structures to JSON array and saves to SharedPreferences.
     *
     * @param prefs Target SharedPreferences instance.
     * @param playlists Playlists to persist.
     */
    fun savePlaylists(prefs: SharedPreferences, playlists: List<Playlist>) {
        try {
            val playlistsJsonArray = JSONArray()
            for (playlist in playlists) {
                val songsJsonArray = JSONArray()
                for (song in playlist.songs) {
                    songsJsonArray.put(
                        JSONObject()
                            .put("id", song.id)
                            .put("t", song.title)
                            .put("a", song.artist)
                    )
                }
                playlistsJsonArray.put(
                    JSONObject()
                        .put("name", playlist.name)
                        .put("imageUri", playlist.imageUri ?: "")
                        .put("description", playlist.description)
                        .put("isFire", playlist.isFire)
                        .put("songData", songsJsonArray)
                )
            }
            prefs.edit().putString(PLAYLISTS_KEY, playlistsJsonArray.toString()).apply()
        } catch (ignored: Exception) {
        }
    }

    /**
     * Parses stored playlist JSON configuration from preferences and associates tracks using lookup maps.
     *
     * @param prefs Source SharedPreferences instance.
     * @param songIdMap Lookup map keyed by track ID.
     * @param songNameMap Lookup map keyed by normalized title and artist.
     * @return List of reconstructed Playlist instances.
     */
    fun parsePlaylists(
        prefs: SharedPreferences,
        songIdMap: Map<String, Song>,
        songNameMap: Map<String, Song>
    ): ArrayList<Playlist> {
        val playlists = ArrayList<Playlist>()
        try {
            val playlistsJsonArray = JSONArray(prefs.getString(PLAYLISTS_KEY, "[]"))
            for (i in 0 until playlistsJsonArray.length()) {
                val playlistJson = playlistsJsonArray.getJSONObject(i)
                val playlist = Playlist(
                    playlistJson.getString("name"),
                    playlistJson.optString("imageUri", "").takeUnless { it.isBlank() }
                )
                playlist.description = playlistJson.optString("description", "")
                playlist.isFire = playlistJson.optBoolean("isFire", false)

                val songsJsonArray = playlistJson.getJSONArray("songData")
                for (j in 0 until songsJsonArray.length()) {
                    val songJson = songsJsonArray.getJSONObject(j)
                    val nameKey = songNameKey(songJson.getString("t"), songJson.getString("a"))
                    val matchedSong = songIdMap[songJson.getString("id")] ?: songNameMap[nameKey]
                    if (matchedSong != null) {
                        playlist.songs.add(matchedSong)
                    }
                }
                playlists.add(playlist)
            }
        } catch (ignored: Exception) {
        }
        return playlists
    }

    /**
     * Serializes playlist collection into a backup file on a background thread.
     *
     * @param context Application context for Toast.
     * @param prefs Source SharedPreferences instance.
     * @param documentUri Target file URI for writing backup data.
     */
    fun exportBackup(context: Context, prefs: SharedPreferences, documentUri: Uri?) {
        if (documentUri == null) return
        backupExecutor.execute {
            try {
                context.contentResolver.openOutputStream(documentUri).use { outputStream ->
                    val playlistsJsonArray = JSONArray(prefs.getString(PLAYLISTS_KEY, "[]"))
                    outputStream?.write(playlistsJsonArray.toString().toByteArray(Charsets.UTF_8))
                    toastOnMain(context, "Export Ready!")
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Parses and restores playlists JSON backup data from a user-selected document.
     *
     * @param context Application context for content resolution.
     * @param prefs Target SharedPreferences instance.
     * @param documentUri Selected backup document URI.
     * @param onRestoreComplete Callback executed on UI thread upon successful restoration.
     */
    fun restoreBackup(context: Context, prefs: SharedPreferences, documentUri: Uri?, onRestoreComplete: () -> Unit) {
        if (documentUri == null) return
        backupExecutor.execute {
            try {
                val inputStream = context.contentResolver.openInputStream(documentUri)
                    ?: throw IOException("Unable to open backup document")
                val backupContent = inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines().joinToString("") }

                prefs.edit().putString(PLAYLISTS_KEY, sanitizeBackup(backupContent)).apply()
                mainHandler.post {
                    onRestoreComplete()
                    Toast.makeText(context, "Restore Successful!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                toastOnMain(context, "Failed to restore backup: Invalid file")
            }
        }
    }

    /**
     * Validates that backup content is a JSON array and strips legacy inline `b64` cover payloads.
     *
     * @param backupContent Raw text of a user-selected backup file.
     * @return Normalized JSON array text safe to store as the playlists preference.
     * @throws org.json.JSONException If the content is not a valid JSON array.
     */
    internal fun sanitizeBackup(backupContent: String): String {
        val validatedArray = JSONArray(backupContent)
        for (i in 0 until validatedArray.length()) {
            validatedArray.optJSONObject(i)?.remove("b64")
        }
        return validatedArray.toString()
    }

    /** Shows a short toast from any thread. */
    private fun toastOnMain(context: Context, message: String) {
        mainHandler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}
