package com.boogie.vibestation.share

import android.content.ContentResolver
import com.boogie.vibestation.util.PlaylistCoverUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Moves a playlist cover between a local image and the Base64 text carried in a [ShareManifest], reusing
 * the same encoding as playlist backups ([PlaylistCoverUtil]).
 */
internal object ShareCovers {

    private const val IMAGE_URI_KEY = "imageUri"
    private const val COVER_KEY = "cover_b64"

    /**
     * Encodes a playlist's cover for sending.
     *
     * @param resolver Resolver used to read the image.
     * @param imageUri The playlist's cover URI, or null.
     * @return Base64 JPEG, or an empty string when there is no readable cover.
     */
    fun encode(resolver: ContentResolver, imageUri: String?): String {
        if (imageUri.isNullOrBlank()) return ""
        val entry = JSONObject().put(IMAGE_URI_KEY, imageUri)
        PlaylistCoverUtil.embedCovers(resolver, JSONArray().put(entry))
        return entry.optString(COVER_KEY, "")
    }

    /**
     * Writes a received cover into app-private storage.
     *
     * @param encoded   Base64 cover from the manifest.
     * @param coversDir Folder for cover files, see [PlaylistCoverUtil.coversDir].
     * @param nowMs     Timestamp that keeps the file name unique.
     * @return URI of the stored image, or null when there was no cover or it could not be written.
     */
    fun store(encoded: String, coversDir: File, nowMs: Long = System.currentTimeMillis()): String? {
        if (encoded.isEmpty()) return null
        val entry = JSONObject().put(COVER_KEY, encoded)
        PlaylistCoverUtil.extractCovers(JSONArray().put(entry), coversDir, nowMs)
        return entry.optString(IMAGE_URI_KEY, "").takeIf { it.isNotBlank() }
    }
}
