package com.boogie.vibestation.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Makes playlist cover art survive backup and restore. Covers are stored as content URIs whose read
 * grant dies with the install, so a backup embeds each cover as a Base64 JPEG (`cover_b64`) and a
 * restore writes it to app-private storage, re-pointing `imageUri` at the local file.
 */
object PlaylistCoverUtil {

    private const val COVERS_DIR = "playlist_covers"
    private const val COVER_KEY = "cover_b64"
    private const val IMAGE_URI_KEY = "imageUri"

    /**
     * Resolves the app-private folder that holds restored cover images.
     *
     * @param context Context supplying the internal files directory.
     * @return The covers directory; it may not exist yet.
     */
    fun coversDir(context: Context): File = File(context.filesDir, COVERS_DIR)

    /**
     * Adds a downsampled Base64 copy of each playlist's cover to the backup JSON. Playlists whose
     * cover cannot be read (revoked grant, deleted image) are left without a `cover_b64` key.
     *
     * @param resolver Resolver used to open each playlist's `imageUri`.
     * @param backup   Playlist JSON objects to annotate in place.
     */
    fun embedCovers(resolver: ContentResolver, backup: JSONArray) {
        for ((_, playlist) in indexedObjects(backup)) {
            val imageUri = playlist.optString(IMAGE_URI_KEY, "")
            val encoded = if (imageUri.isBlank()) "" else ArtUtil.getBase64Image(resolver, Uri.parse(imageUri))
            if (encoded.isNotEmpty()) playlist.put(COVER_KEY, encoded)
        }
    }

    /**
     * Writes each embedded cover to [coversDir] and re-points its playlist's `imageUri` at the new
     * file. `cover_b64` is always removed so the payload never reaches SharedPreferences; a cover
     * that fails to decode or write keeps its original `imageUri`.
     *
     * @param backup    Playlist JSON objects to rewrite in place.
     * @param coversDir Destination folder, created if missing.
     * @param nowMs     Timestamp used to keep file names unique across restores.
     */
    fun extractCovers(backup: JSONArray, coversDir: File, nowMs: Long = System.currentTimeMillis()) {
        for ((i, playlist) in indexedObjects(backup)) {
            val encoded = playlist.optString(COVER_KEY, "")
            playlist.remove(COVER_KEY)
            val coverFile = if (encoded.isEmpty()) null else writeCover(coversDir, "playlist_${nowMs}_$i.jpg", encoded)
            if (coverFile != null) playlist.put(IMAGE_URI_KEY, Uri.fromFile(coverFile).toString())
        }
    }

    /**
     * Lists the non-blank `imageUri` values of a playlist JSON array.
     *
     * @param backup Playlist JSON objects to read.
     * @return Cover URIs in array order.
     */
    fun imageUris(backup: JSONArray): List<String> =
        indexedObjects(backup).map { (_, playlist) -> playlist.optString(IMAGE_URI_KEY, "") }.filter { it.isNotBlank() }

    /**
     * Deletes cover files in [coversDir] that no playlist points at any more, such as after a
     * playlist is deleted, its cover replaced, or a restore swaps in a different set of playlists.
     *
     * @param coversDir     Folder holding restored covers.
     * @param referencedUris `imageUri` values of every playlist that must keep its cover.
     */
    fun deleteOrphans(coversDir: File, referencedUris: Collection<String>) {
        val files = coversDir.listFiles() ?: return
        val keep = referencedUris
            .filter { it.contains("/$COVERS_DIR/") }
            .map { it.substringAfterLast('/') }
            .toSet()
        files.filter { it.name !in keep }.forEach { it.delete() }
    }

    /** Pairs each JSON object in [backup] with its array index, skipping entries that are not objects. */
    private fun indexedObjects(backup: JSONArray): List<Pair<Int, JSONObject>> =
        (0 until backup.length()).mapNotNull { index -> backup.optJSONObject(index)?.let { index to it } }

    /** Decodes [encoded] into a new file named [name] under [dir], or null if it is empty or unwritable. */
    private fun writeCover(dir: File, name: String, encoded: String): File? {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.isEmpty() || !(dir.isDirectory || dir.mkdirs())) return null
            File(dir, name).also { it.writeBytes(bytes) }
        } catch (ignored: Exception) {
            null
        }
    }
}
