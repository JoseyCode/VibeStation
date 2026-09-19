package com.boogie.vibestation.util

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles physical audio file metadata tag reading, ID3 modification, and media scanner re-indexing.
 */
object MediaMetadataUtil {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * Updates ID3 title, artist, and album tags for an individual audio file.
     *
     * @param context Application context for media scanner and notifications.
     * @param song Target song model.
     * @param newTitle Updated track title.
     * @param newArtist Updated track artist.
     * @param newAlbum Updated track album name.
     * @param onComplete Callback invoked on UI thread after successful media rescan.
     */
    fun updateSongMetadata(
        context: Context,
        song: Song,
        newTitle: String,
        newArtist: String,
        newAlbum: String,
        onComplete: () -> Unit
    ) {
        ioExecutor.execute {
            try {
                val path = song.requirePath()
                val edited = editTag(path) { tag ->
                    tag.setField(FieldKey.TITLE, newTitle)
                    tag.setField(FieldKey.ARTIST, newArtist)
                    tag.setField(FieldKey.ALBUM, newAlbum)
                }
                if (edited) {
                    scanFilesAndNotify(context, arrayOf(path), "Metadata updated successfully", onComplete)
                }
            } catch (e: Exception) {
                handleMetadataError(context, "Failed to update metadata", e)
            }
        }
    }

    /**
     * Updates ID3 tags across all physical audio tracks associated with an album.
     *
     * @param context Application context for media scanner and notifications.
     * @param album Target album model containing child songs.
     * @param newAlbum Updated album name.
     * @param newArtist Updated artist name.
     * @param onComplete Callback invoked on UI thread after scan completion.
     */
    fun updateAlbumMetadata(context: Context, album: Album, newAlbum: String, newArtist: String, onComplete: () -> Unit) {
        ioExecutor.execute {
            try {
                for (song in album.songs) {
                    editTag(song.requirePath()) { tag ->
                        tag.setField(FieldKey.ALBUM, newAlbum)
                        tag.setField(FieldKey.ARTIST, newArtist)
                    }
                }
                scanFilesAndNotify(context, extractPaths(album), "Album metadata updated", onComplete)
            } catch (e: Exception) {
                handleMetadataError(context, "Failed to update metadata", e)
            }
        }
    }

    /**
     * Updates physical embedded artwork tags across all audio tracks in an album.
     *
     * @param context Application context for content resolution and media scanning.
     * @param album Target album model.
     * @param imageUri Content URI referencing the new cover image.
     * @param onComplete Callback invoked on UI thread after scan completion.
     */
    fun updateAlbumArt(context: Context, album: Album, imageUri: Uri, onComplete: () -> Unit) {
        ioExecutor.execute {
            try {
                val imageData = readUriBytes(context, imageUri) ?: return@execute

                val artwork = ArtworkFactory.getNew().apply {
                    binaryData = imageData
                    mimeType = "image/jpeg"
                    pictureType = PictureTypes.DEFAULT_ID
                }
                for (song in album.songs) {
                    editTag(song.requirePath()) { tag ->
                        tag.deleteArtworkField()
                        tag.setField(artwork)
                    }
                }
                scanFilesAndNotify(context, extractPaths(album), "Album cover updated", onComplete)
            } catch (e: Exception) {
                handleMetadataError(context, "Failed to update cover", e)
            }
        }
    }

    /**
     * Deletes physical files for all songs in an album and requests MediaScanner removal.
     *
     * @param context Application context.
     * @param album Target album to delete.
     * @param onComplete Callback invoked after rescan.
     */
    fun deleteAlbum(context: Context, album: Album, onComplete: () -> Unit) {
        ioExecutor.execute {
            try {
                val paths = extractPaths(album)
                for (path in paths) {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
                scanFilesAndNotify(context, paths, "Album deleted", onComplete)
            } catch (e: Exception) {
                handleMetadataError(context, "Failed to delete album", e)
            }
        }
    }

    /**
     * Triggers media scanner indexing on target file paths and dispatches toast feedback.
     *
     * @param context Application context.
     * @param paths File paths to index.
     * @param successMessage Toast text upon full scan completion.
     * @param onComplete Post-scan callback.
     */
    fun scanFilesAndNotify(context: Context, paths: Array<String>, successMessage: String, onComplete: () -> Unit) {
        val notifyDone = {
            mainHandler.post {
                Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
        if (paths.isEmpty()) {
            notifyDone()
            return
        }
        val remaining = AtomicInteger(paths.size)
        MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
            if (remaining.decrementAndGet() == 0) notifyDone()
        }
    }

    /**
     * Applies [edit] to a file's tag (creating one if absent) and writes it back to disk.
     *
     * @return False if the file yields no writable tag, true once the edit is written.
     */
    private fun editTag(path: String, edit: (Tag) -> Unit): Boolean {
        val audioFile = AudioFileIO.read(File(path))
        val tag = audioFile.tagOrCreateAndSetDefault ?: return false
        edit(tag)
        AudioFileIO.write(audioFile)
        return true
    }

    private fun Song.requirePath(): String = requireNotNull(path) { "Song has no file path" }

    /** Dispatches an error message toast to the user interface thread. */
    private fun handleMetadataError(context: Context, prefix: String, exception: Exception) {
        mainHandler.post { Toast.makeText(context, "$prefix: ${exception.message}", Toast.LENGTH_LONG).show() }
    }

    /** Absolute paths of every album track that has a backing file. */
    private fun extractPaths(album: Album): Array<String> = album.songs.mapNotNull { it.path }.toTypedArray()

    /** Reads all bytes from a content URI, or null if it cannot be opened or read. */
    private fun readUriBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (ignored: Exception) {
        null
    }
}
