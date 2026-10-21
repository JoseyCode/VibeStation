package com.boogie.vibestation

import android.content.SharedPreferences
import android.util.Log
import com.boogie.vibestation.util.CoverImage
import com.boogie.vibestation.util.PendingDeletions
import com.boogie.vibestation.util.PlaylistSync
import com.boogie.vibestation.util.PlaylistUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The playlist half of a sync run: covers and playlists exchanged with the web server. The network
 * calls live here; the merge rules are in [PlaylistSync] and the server's `lib/playlists.js`.
 */
object PlaylistSyncManager {

    private const val TAG = "VibeSync"

    /**
     * Exchanges playlists with the server: uploads covers it does not have, posts every playlist together
     * with the deletions made since the last sync, and stores the merged answer. Playlists, fields and
     * tracks never disappear because they were merely missing from a request, so a stale device cannot
     * erase newer data. Failures are logged and leave the local playlists untouched.
     *
     * @param prefs      Preferences holding the playlists and pending deletions.
     * @param client     OkHttpClient.
     * @param serverUrl  Destination server base URL.
     * @param coversDir  Folder that downloaded cover images are stored in.
     * @param readImage  Reads the image behind a cover URI, or null when it cannot be read.
     */
    internal fun sync(
        prefs: SharedPreferences,
        client: OkHttpClient,
        serverUrl: String,
        coversDir: File,
        readImage: (String) -> CoverImage?
    ) {
        try {
            val storedJson = prefs.getString(PlaylistUtil.PLAYLISTS_KEY, "[]") ?: "[]"
            val pending = PendingDeletions(prefs)
            val deleted = pending.read()
            val playlists = JSONArray(storedJson)
            for (index in PlaylistSync.coversNeedingUpload(playlists)) {
                val playlist = playlists.getJSONObject(index)
                val cover = readImage(playlist.getString("imageUri"))?.let { uploadCover(client, serverUrl, it) }
                if (cover != null) playlist.put("cover", cover)
            }

            val request = Request.Builder()
                .url("$serverUrl/api/playlists")
                .post(PlaylistSync.buildRequest(playlists, deleted).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val merged = client.newCall(request).execute().use { response ->
                SyncManager.requireSuccess(response)
                JSONArray(response.body?.string() ?: throw IOException("Empty server response"))
            }
            val reconciled = PlaylistSync.reconcile(playlists, merged, coversDir) { name, file ->
                downloadCover(client, serverUrl, name, file)
            }

            // Edits made while the sync ran stay in preferences and go out with the next one
            if (prefs.getString(PlaylistUtil.PLAYLISTS_KEY, "[]") == storedJson) {
                prefs.edit().putString(PlaylistUtil.PLAYLISTS_KEY, reconciled.toString()).apply()
            }
            pending.removeSent(deleted)
        } catch (e: IOException) {
            Log.e(TAG, "Playlist sync failed", e)
        } catch (e: JSONException) {
            Log.e(TAG, "Playlist sync got unreadable data", e)
        }
    }

    /**
     * Uploads a cover image at its original quality.
     *
     * @param image The image bytes and MIME type.
     * @return The name the server stored it under, or null when the upload was rejected or failed.
     */
    private fun uploadCover(client: OkHttpClient, serverUrl: String, image: CoverImage): String? = try {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("cover", "cover", image.bytes.toRequestBody(image.mimeType.toMediaType()))
            .build()
        client.newCall(Request.Builder().url("$serverUrl/api/covers").post(body).build()).execute().use { response ->
            SyncManager.requireSuccess(response)
            JSONObject(response.body?.string().orEmpty()).optString("cover").takeIf { PlaylistSync.isCoverName(it) }
        }
    } catch (e: IOException) {
        Log.e(TAG, "Cover upload failed", e)
        null
    }

    /**
     * Downloads a stored cover to [target], writing a temporary file first so an interrupted download
     * is never mistaken for a finished cover. [name] is already checked by [PlaylistSync.isCoverName],
     * so it is safe in a URL as it is.
     *
     * @return Whether the cover is now at [target].
     */
    private fun downloadCover(client: OkHttpClient, serverUrl: String, name: String, target: File): Boolean = try {
        client.newCall(Request.Builder().url("$serverUrl/api/covers/$name").build()).execute().use { response ->
            SyncManager.requireSuccess(response)
            val body = response.body ?: throw IOException("Empty cover response")
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "$name.part")
            partial.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
            partial.renameTo(target)
        }
    } catch (e: IOException) {
        Log.e(TAG, "Cover download failed: $name", e)
        false
    }
}
