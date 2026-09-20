package com.boogie.vibestation.share

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.boogie.vibestation.util.MediaStoreWriter
import com.boogie.vibestation.util.MusicLibraryUtil
import com.boogie.vibestation.util.PlaylistCoverUtil
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/**
 * [ShareHost] backed by MediaStore, the app's private cover folder, and the playlists preference. It is
 * glue over framework calls; the decisions it feeds (naming, paths, playlist entries) are in [ShareImport].
 *
 * @param context Any context; only the application context is kept.
 */
internal class AndroidShareHost(context: Context) : ShareHost {
    private val app = context.applicationContext
    private val playlists = PlaylistStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    override fun localTracks(): List<LocalTrack> {
        val songs = MusicLibraryUtil.queryMediaStoreSongs(app.contentResolver, null, null)
        val files = queryShareFiles(app.contentResolver, songs.map { it.id })
        return songs.map { LocalTrack(it.id, it.title, it.artist, files[it.id]?.durationMs ?: 0L) }
    }

    override fun freeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    override fun storeTrack(track: ShareTrack, input: InputStream): LocalTrack {
        val id = try {
            MediaStoreWriter.insertTrack(app.contentResolver, ShareImport.newTrack(track)) { input.copyTo(it) }
        } catch (e: IllegalStateException) {
            throw IOException("Could not store ${track.title}", e)
        } catch (e: SecurityException) {
            throw IOException("Not allowed to store ${track.title}", e)
        }
        return LocalTrack(id.toString(), track.title, track.artist, track.durationMs)
    }

    override fun existingPlaylistNames(): List<String> = playlists.names()

    override fun storeCover(encoded: String): String? = ShareCovers.store(encoded, PlaylistCoverUtil.coversDir(app))

    override fun addPlaylist(entry: JSONObject) = playlists.add(entry)

    private companion object {
        /** Name of the preferences file the whole app uses; MainActivity and SyncManager open the same one. */
        const val PREFS_NAME = "RetroPrefs"
    }
}
