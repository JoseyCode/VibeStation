package com.boogie.vibestation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.MediaStoreWriter
import com.boogie.vibestation.util.NewTrack
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages the background synchronization process between the Android app's local MediaStore
 * audio database and a remote VibeStation server. Handles song uploads, downloads,
 * and JSON-based playlist syncing.
 */
object SyncManager {

    private const val TAG = "VibeSync"

    // Executor that serializes sync execution runs to prevent race conditions on write operations
    private val syncExecutor = Executors.newSingleThreadExecutor()

    /**
     * Callback interface providing progress, completion, and failure feedback
     * notifications during a synchronization run.
     */
    interface SyncCallback {
        /** Called when a synchronization step completes. */
        fun onProgress(progress: Int, max: Int, message: String)

        /** Called when the synchronization run completes successfully. */
        fun onComplete(result: String)

        /** Called when a critical exception terminates the synchronization run. */
        fun onError(error: String)
    }

    /**
     * Starts the synchronization operation on a background executor. Querying remote songs,
     * comparing match keys to compute uploads and downloads, and merging playlists.
     *
     * @param context    Application context for content resolvers and preferences.
     * @param serverUrl  Base URL of the remote synchronization server.
     * @param localSongs All local songs found in the MediaStore database.
     * @param callback   Callback for reporting sync status updates to the UI.
     */
    fun startSync(context: Context, serverUrl: String, localSongs: List<Song>, callback: SyncCallback) {
        syncExecutor.execute {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            try {
                callback.onProgress(0, 0, "Querying server library...")
                Log.d(TAG, "Connecting to server: $serverUrl")

                val remoteSongs = fetchRemoteSongs(client, serverUrl)
                val remoteKeys = remoteSongs.mapTo(HashSet()) { makeMatchKey(it.title, it.artist) }
                val localKeys = localSongs.mapTo(HashSet()) { makeMatchKey(it.title, it.artist) }

                val uploadList = localSongs.filter { makeMatchKey(it.title, it.artist) !in remoteKeys }
                val downloadList = remoteSongs.filter { makeMatchKey(it.title, it.artist) !in localKeys }

                val total = uploadList.size + downloadList.size
                val uploaded = transferAll(uploadList, "Uploading", "upload", 0, total, callback, { it.title }) {
                    uploadSong(client, serverUrl, it)
                }
                val downloaded = transferAll(downloadList, "Downloading", "download", uploadList.size, total, callback, { it.title }) {
                    downloadSong(context, client, serverUrl, it)
                }

                callback.onProgress(total, total, "Syncing playlists database...")
                syncPlaylists(context, client, serverUrl)

                callback.onComplete("Uploaded $uploaded tracks, Downloaded $downloaded tracks.")
            } catch (e: Exception) {
                Log.e(TAG, "Sync process crashed with exception", e)
                callback.onError(e.toString())
            }
        }
    }

    /**
     * Fetches remote song catalog metadata from the sync server.
     *
     * @param client Configured HTTP client.
     * @param serverUrl Base URL of the sync server.
     * @return List of parsed remote songs.
     * @throws IOException If server network communication fails.
     * @throws org.json.JSONException If server response JSON parsing fails.
     */
    internal fun fetchRemoteSongs(client: OkHttpClient, serverUrl: String): List<RemoteSong> {
        val request = Request.Builder().url("$serverUrl/api/songs").build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server error: ${response.code}")
            response.body?.string() ?: throw IOException("Empty server response")
        }

        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RemoteSong(
                id = obj.optString("id", ""),
                title = obj.optString("title", "Unknown Title"),
                artist = obj.optString("artist", "Unknown Artist"),
                album = obj.optString("album", "Unknown Album")
            )
        }
    }

    /**
     * Runs [transfer] for each item, reporting progress and logging failures without aborting the batch.
     *
     * @param items Items to transfer.
     * @param progressLabel Gerund shown in progress messages (e.g. "Uploading").
     * @param failureLabel Verb shown in failure logs (e.g. "upload").
     * @param progressOffset Number of operations already completed before this batch.
     * @param total Total operations in the whole sync.
     * @param titleOf Extracts the display title for messages.
     * @return Number of items transferred without throwing.
     */
    internal fun <T> transferAll(
        items: List<T>,
        progressLabel: String,
        failureLabel: String,
        progressOffset: Int,
        total: Int,
        callback: SyncCallback,
        titleOf: (T) -> String,
        transfer: (T) -> Unit
    ): Int {
        var succeeded = 0
        items.forEachIndexed { index, item ->
            val current = progressOffset + index
            callback.onProgress(current, total, "$progressLabel (${current + 1}/$total):\n${titleOf(item)}")
            try {
                transfer(item)
                succeeded++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to $failureLabel: ${titleOf(item)}", e)
            }
        }
        return succeeded
    }

    /**
     * Generates a normalized match key based on alphanumeric characters in song metadata.
     * Prevents metadata differences (whitespace, casing, punctuation) from causing duplicates.
     *
     * @param title  The song title.
     * @param artist The song artist.
     * @return       A clean lowercase identification key.
     */
    internal fun makeMatchKey(title: String, artist: String): String {
        val safeTitle = title.trim()
        val safeArtist = artist.trim()
        if (safeTitle.isEmpty() && safeArtist.isEmpty()) return "unknown_track"
        return "${safeTitle}_$safeArtist".lowercase(Locale.getDefault()).replace(Regex("[^\\p{L}\\p{N}_]"), "")
    }

    /**
     * Sanitizes file system input strings to prevent invalid characters from causing crash loops during downloads.
     *
     * @param name Name string to sanitize.
     * @return     A filesystem-safe name string.
     */
    internal fun safeFileName(name: String): String {
        if (name.isBlank()) return "track_${System.currentTimeMillis()}"
        return name.replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_")
    }

    /**
     * Performs a multipart POST request containing the raw audio file to upload it to the server.
     *
     * @param client    Initialized OkHttpClient.
     * @param serverUrl Server destination base URL.
     * @param song      The local Song object to upload.
     */
    internal fun uploadSong(client: OkHttpClient, serverUrl: String, song: Song) {
        val file = File(song.path ?: return)
        if (!file.exists()) return

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody("audio/mpeg".toMediaType()))
            .build()
        val request = Request.Builder().url("$serverUrl/api/upload").post(requestBody).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Upload failed for ${song.title}")
        }
    }

    /**
     * Streams the audio file content from the server and inserts it into the Android MediaStore content provider
     * through [MediaStoreWriter].
     *
     * @param context    Application context for content resolution.
     * @param client     OkHttpClient.
     * @param serverUrl  Server source base URL.
     * @param remoteSong The RemoteSong description of the track to download.
     */
    private fun downloadSong(context: Context, client: OkHttpClient, serverUrl: String, remoteSong: RemoteSong) {
        val request = Request.Builder().url("$serverUrl/api/stream/${Uri.encode(remoteSong.id)}").build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download stream")
            val body = response.body ?: throw IOException("Empty response body from stream")

            val track = NewTrack(
                displayName = safeFileName(remoteSong.title) + ".mp3",
                title = remoteSong.title,
                artist = remoteSong.artist,
                album = remoteSong.album,
                mimeType = "audio/mpeg",
                relativePath = "Music/"
            )
            MediaStoreWriter.insertTrack(context.contentResolver, track) { output ->
                body.byteStream().use { it.copyTo(output) }
            }
        }
    }

    /**
     * Posts local JSON-formatted playlists to the server, fetches the merged response database,
     * and saves it locally inside the app's Shared Preferences database.
     *
     * @param context   Application context.
     * @param client    OkHttpClient.
     * @param serverUrl Destination server base URL.
     */
    internal fun syncPlaylists(context: Context, client: OkHttpClient, serverUrl: String) {
        try {
            val prefs = context.getSharedPreferences("RetroPrefs", Context.MODE_PRIVATE)
            val localPlaylistsJson = prefs.getString("playlists", "[]") ?: "[]"

            val request = Request.Builder()
                .url("$serverUrl/api/playlists")
                .post(localPlaylistsJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val mergedPlaylistsJson = if (response.isSuccessful) response.body?.string() else null
                if (mergedPlaylistsJson != null) {
                    prefs.edit().putString("playlists", mergedPlaylistsJson).apply()
                }
            }
        } catch (ignored: Exception) {
        }
    }

    /** Local model representing a remote server track definition metadata block. */
    internal class RemoteSong(val id: String, val title: String, val artist: String, val album: String)
}
