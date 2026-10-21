package com.boogie.vibestation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.AudioFormats
import com.boogie.vibestation.util.MatchKey
import com.boogie.vibestation.util.MediaStoreWriter
import com.boogie.vibestation.util.NewTrack
import com.boogie.vibestation.util.PlaylistCoverUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Manages the background synchronization process between the Android app's local MediaStore
 * audio database and a remote VibeStation server. Handles song uploads, downloads,
 * and JSON-based playlist syncing.
 */
object SyncManager {

    private const val TAG = "VibeSync"
    private const val PREFS_NAME = "RetroPrefs"
    private const val HTTP_UNAUTHORIZED = 401
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    /** Header the server checks against its `VIBE_PASSWORD`. */
    internal const val PASSWORD_HEADER = "X-Vibe-Password"

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
     * @param password   Shared server password; blank sends none.
     * @param localSongs All local songs found in the MediaStore database.
     * @param callback   Callback for reporting sync status updates to the UI.
     */
    fun startSync(context: Context, serverUrl: String, password: String, localSongs: List<Song>, callback: SyncCallback) {
        syncExecutor.execute {
            val client = buildClient(password)

            try {
                callback.onProgress(0, 0, "Querying server library...")
                Log.d(TAG, "Connecting to server: $serverUrl")

                val remoteSongs = fetchRemoteSongs(client, serverUrl)
                val remoteKeys = remoteSongs.mapTo(HashSet()) { makeMatchKey(it.title, it.artist) }
                val localKeys = localSongs.mapTo(HashSet()) { makeMatchKey(it.title, it.artist) }

                // The server only stores formats in AudioFormats, so anything else would be retried forever
                val uploadList = localSongs.filter {
                    makeMatchKey(it.title, it.artist) !in remoteKeys && AudioFormats.isSupported(it.path.orEmpty())
                }
                val downloadList = remoteSongs.filter { makeMatchKey(it.title, it.artist) !in localKeys }

                val total = uploadList.size + downloadList.size
                val uploaded = transferAll(uploadList, "Uploading", "upload", 0, total, callback, { it.title }) {
                    uploadSong(client, serverUrl, it)
                }
                val downloaded = transferAll(downloadList, "Downloading", "download", uploadList.size, total, callback, { it.title }) {
                    downloadSong(context, client, serverUrl, it)
                }

                callback.onProgress(total, total, "Syncing playlists database...")
                PlaylistSyncManager.sync(
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                    client,
                    serverUrl,
                    PlaylistCoverUtil.coversDir(context)
                ) { PlaylistCoverUtil.readImage(context.contentResolver, it) }

                callback.onComplete("Uploaded $uploaded tracks, Downloaded $downloaded tracks.")
            } catch (e: Exception) {
                Log.e(TAG, "Sync process crashed with exception", e)
                callback.onError(e.toString())
            }
        }
    }

    /**
     * Builds the sync HTTP client, attaching [password] to every request so uploads, streams and
     * playlist posts all authenticate, not just the first call.
     *
     * @param password Shared server password; blank sends no header.
     * @return Configured HTTP client.
     */
    internal fun buildClient(password: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(
                if (password.isBlank()) request else request.newBuilder().header(PASSWORD_HEADER, password).build()
            )
        }
        .build()

    /**
     * Fetches remote song catalog metadata from the sync server.
     *
     * @param client Configured HTTP client.
     * @param serverUrl Base URL of the sync server.
     * @return List of parsed remote songs.
     * @throws IOException If server network communication fails or the password is rejected.
     * @throws org.json.JSONException If server response JSON parsing fails.
     */
    internal fun fetchRemoteSongs(client: OkHttpClient, serverUrl: String): List<RemoteSong> {
        val request = Request.Builder().url("$serverUrl/api/songs").build()
        val json = client.newCall(request).execute().use { response ->
            requireSuccess(response)
            response.body?.string() ?: throw IOException("Empty server response")
        }

        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RemoteSong(
                id = obj.optString("id", ""),
                title = obj.optString("title", "Unknown Title"),
                artist = obj.optString("artist", "Unknown Artist"),
                album = obj.optString("album", "Unknown Album"),
                format = obj.optString("format", "")
            )
        }
    }

    /**
     * Cleans a typed server address: trims it, drops trailing slashes, and assumes `http://` when no
     * scheme is given, since a bare `192.168.1.5:1337` makes OkHttp reject the URL.
     *
     * @param raw Address as typed by the user.
     * @return Normalized base URL, or an empty string for blank input.
     */
    fun normalizeServerUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.contains("://")) trimmed else "http://$trimmed"
    }

    /**
     * Throws when [response] failed, naming a rejected password separately from other server errors.
     *
     * @param response Response to check.
     * @throws IOException If the response is not successful.
     */
    internal fun requireSuccess(response: Response) {
        if (response.isSuccessful) return
        throw IOException(
            if (response.code == HTTP_UNAUTHORIZED) "Wrong or missing sync password" else "Server error: ${response.code}"
        )
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
    internal fun makeMatchKey(title: String, artist: String): String = MatchKey.of(title, artist)

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
            .addFormDataPart("files", file.name, file.asRequestBody(AudioFormats.mimeOf(file.name).toMediaType()))
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

            val extension = AudioFormats.extensionForFormat(remoteSong.format)
            val track = NewTrack(
                displayName = safeFileName(remoteSong.title) + "." + extension,
                title = remoteSong.title,
                artist = remoteSong.artist,
                album = remoteSong.album,
                mimeType = AudioFormats.mimeForExtension(extension) ?: "audio/mpeg",
                relativePath = "Music/"
            )
            MediaStoreWriter.insertTrack(context.contentResolver, track) { output ->
                body.byteStream().use { it.copyTo(output) }
            }
        }
    }

    /** Local model representing a remote server track definition metadata block. */
    internal class RemoteSong(val id: String, val title: String, val artist: String, val album: String, val format: String = "")
}
