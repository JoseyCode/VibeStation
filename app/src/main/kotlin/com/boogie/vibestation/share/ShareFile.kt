package com.boogie.vibestation.share

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore

/**
 * File details for one local song that the [com.boogie.vibestation.models.Song] model does not carry.
 *
 * @property id          MediaStore audio row id, as a string.
 * @property displayName File name including extension.
 * @property mimeType    MIME type, empty if MediaStore does not report one.
 * @property sizeBytes   File size in bytes.
 * @property durationMs  Length in milliseconds, 0 if unknown.
 */
internal data class ShareFile(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long
)

private val SHARE_FILE_PROJECTION = arrayOf(
    MediaStore.Audio.Media._ID,
    MediaStore.Audio.Media.DISPLAY_NAME,
    MediaStore.Audio.Media.MIME_TYPE,
    MediaStore.Audio.Media.SIZE,
    MediaStore.Audio.Media.DURATION
)

// Stays well under SQLite's 999 bound-variable limit
private const val ID_CHUNK = 500

/**
 * Reads [ShareFile] rows for the given songs from MediaStore.
 *
 * @param resolver Resolver used to query MediaStore.
 * @param songIds  MediaStore ids of the songs to look up.
 * @return File details keyed by song id; ids MediaStore does not know are absent.
 */
internal fun queryShareFiles(resolver: ContentResolver, songIds: Collection<String>): Map<String, ShareFile> {
    val result = HashMap<String, ShareFile>()
    for (chunk in songIds.distinct().chunked(ID_CHUNK)) {
        val selection = "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",") { "?" }})"
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, SHARE_FILE_PROJECTION, selection, chunk.toTypedArray(), null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val file = shareFileFromCursor(cursor)
                result[file.id] = file
            }
        }
    }
    return result
}

/**
 * Maps the current row of a cursor built with [SHARE_FILE_PROJECTION] to a [ShareFile].
 *
 * @param cursor Positioned MediaStore query cursor.
 * @return The row's file details; missing text columns become empty strings.
 */
internal fun shareFileFromCursor(cursor: Cursor): ShareFile {
    fun column(name: String) = cursor.getColumnIndexOrThrow(name)
    return ShareFile(
        id = cursor.getString(column(MediaStore.Audio.Media._ID)) ?: "",
        displayName = cursor.getString(column(MediaStore.Audio.Media.DISPLAY_NAME)) ?: "",
        mimeType = cursor.getString(column(MediaStore.Audio.Media.MIME_TYPE)) ?: "",
        sizeBytes = cursor.getLong(column(MediaStore.Audio.Media.SIZE)),
        durationMs = cursor.getLong(column(MediaStore.Audio.Media.DURATION))
    )
}
