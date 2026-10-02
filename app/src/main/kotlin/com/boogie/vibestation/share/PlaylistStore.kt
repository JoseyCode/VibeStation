package com.boogie.vibestation.share

import android.content.SharedPreferences
import com.boogie.vibestation.util.PlaylistUtil
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Reads and appends to the playlists the app stores as JSON in SharedPreferences, without going through
 * the activity's in-memory list. A screen that already holds the playlists must reload them afterwards.
 *
 * @param prefs The app's preferences, where [PlaylistUtil.PLAYLISTS_KEY] holds the playlists.
 */
internal class PlaylistStore(private val prefs: SharedPreferences) {

    /**
     * Lists the stored playlist names.
     *
     * @return Names in stored order; empty if none are stored or the stored text is unreadable.
     */
    fun names(): List<String> = try {
        ShareImport.playlistNames(stored())
    } catch (ignored: JSONException) {
        emptyList()
    }

    /**
     * Appends a playlist. Unreadable stored playlists are left untouched rather than replaced.
     *
     * @param entry Playlist in the stored format.
     * @throws IOException If the stored playlists are unreadable or the write failed.
     */
    @Throws(IOException::class)
    fun add(entry: JSONObject) {
        val updated = try {
            ShareImport.appendPlaylist(stored(), entry)
        } catch (e: JSONException) {
            throw IOException("Stored playlists are unreadable", e)
        }
        if (!prefs.edit().putString(PlaylistUtil.PLAYLISTS_KEY, updated).commit()) {
            throw IOException("Could not save playlists")
        }
    }

    private fun stored(): String = prefs.getString(PlaylistUtil.PLAYLISTS_KEY, null) ?: "[]"
}
