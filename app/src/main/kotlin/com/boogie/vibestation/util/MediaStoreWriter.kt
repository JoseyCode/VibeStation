package com.boogie.vibestation.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/**
 * Describes one audio file to be added to the device's MediaStore library.
 *
 * @property displayName  File name including extension, already safe for the file system.
 * @property title        Track title stored in the MediaStore record.
 * @property artist       Artist stored in the MediaStore record.
 * @property album        Album stored in the MediaStore record.
 * @property mimeType     MIME type of the audio data, for example "audio/mpeg".
 * @property relativePath Folder under shared storage (for example "Music/"), used on Android 10+.
 */
internal data class NewTrack(
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val mimeType: String,
    val relativePath: String
)

/**
 * Inserts audio files into the Android MediaStore. Shared by every feature that receives songs
 * from elsewhere (server sync, nearby sharing) so they all get the same pending-flag handling and
 * cleanup of half-written records.
 */
internal object MediaStoreWriter {

    /**
     * Creates a MediaStore audio record, lets [write] fill it with the file bytes, and publishes it.
     * On Android 10+ the record is hidden (IS_PENDING) until the write succeeds. If anything fails,
     * the partial record is deleted so no empty or truncated track shows up in the library.
     *
     * @param resolver Content resolver used for the insert, write, and update.
     * @param track Metadata and destination of the new file.
     * @param write Writes the audio bytes into the provided stream; the writer closes it.
     * @return The MediaStore id of the new record, valid on this device only.
     * @throws IOException If the record cannot be created or written.
     */
    fun insertTrack(resolver: ContentResolver, track: NewTrack, write: (OutputStream) -> Unit): Long {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, track.displayName)
            put(MediaStore.Audio.Media.TITLE, track.title)
            put(MediaStore.Audio.Media.ARTIST, track.artist)
            put(MediaStore.Audio.Media.ALBUM, track.album)
            put(MediaStore.Audio.Media.MIME_TYPE, track.mimeType)
            // Scoped storage requirements for Android 10 (Q) and higher
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, track.relativePath)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        var insertedUri: Uri? = null
        var succeeded = false
        try {
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Failed to insert MediaStore record")
            insertedUri = uri

            val output = resolver.openOutputStream(uri) ?: throw IOException("Failed to open MediaStore output")
            output.use(write)

            // Turn off pending status flag once writing successfully completes on Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            succeeded = true
            return ContentUris.parseId(uri)
        } finally {
            // Delete orphaned provider entries in the event of a failure during writing
            if (!succeeded) insertedUri?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }
}
