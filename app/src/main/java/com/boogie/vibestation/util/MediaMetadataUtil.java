package com.boogie.vibestation.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.boogie.vibestation.models.Album;
import com.boogie.vibestation.models.Song;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.reference.PictureTypes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles physical audio file metadata tag reading, ID3 modification, and media scanner re-indexing.
 */
public final class MediaMetadataUtil {

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaMetadataUtil() {
        // Prevent instantiation
    }

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
    public static void updateSongMetadata(Context context, Song song, String newTitle, String newArtist, String newAlbum, Runnable onComplete) {
        new Thread(() -> {
            try {
                File file = new File(song.path);
                AudioFile audioFile = AudioFileIO.read(file);
                Tag tag = audioFile.getTagOrCreateAndSetDefault();
                if (tag != null) {
                    tag.setField(FieldKey.TITLE, newTitle);
                    tag.setField(FieldKey.ARTIST, newArtist);
                    tag.setField(FieldKey.ALBUM, newAlbum);
                    AudioFileIO.write(audioFile);
                    scanFilesAndNotify(context, new String[]{song.path}, "Metadata updated successfully", onComplete);
                }
            } catch (Exception e) {
                handleMetadataError(context, "Failed to update metadata", e);
            }
        }).start();
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
    public static void updateAlbumMetadata(Context context, Album album, String newAlbum, String newArtist, Runnable onComplete) {
        new Thread(() -> {
            try {
                for (Song song : album.songs) {
                    File file = new File(song.path);
                    AudioFile audioFile = AudioFileIO.read(file);
                    Tag tag = audioFile.getTag();
                    if (tag != null) {
                        tag.setField(FieldKey.ALBUM, newAlbum);
                        tag.setField(FieldKey.ARTIST, newArtist);
                        AudioFileIO.write(audioFile);
                    }
                }
                String[] paths = extractPaths(album);
                scanFilesAndNotify(context, paths, "Album metadata updated", onComplete);
            } catch (Exception e) {
                handleMetadataError(context, "Failed to update metadata", e);
            }
        }).start();
    }

    /**
     * Updates physical embedded artwork tags across all audio tracks in an album.
     *
     * @param context Application context for content resolution and media scanning.
     * @param album Target album model.
     * @param imageUri Content URI referencing the new cover image.
     * @param onComplete Callback invoked on UI thread after scan completion.
     */
    public static void updateAlbumArt(Context context, Album album, Uri imageUri, Runnable onComplete) {
        new Thread(() -> {
            try {
                byte[] imageData = readUriBytes(context, imageUri);
                if (imageData == null) return;

                Artwork artwork = ArtworkFactory.getNew();
                artwork.setBinaryData(imageData);
                artwork.setMimeType("image/jpeg");
                artwork.setPictureType(PictureTypes.DEFAULT_ID);

                for (Song song : album.songs) {
                    File file = new File(song.path);
                    AudioFile audioFile = AudioFileIO.read(file);
                    Tag tag = audioFile.getTag();
                    if (tag != null) {
                        tag.deleteArtworkField();
                        tag.setField(artwork);
                        AudioFileIO.write(audioFile);
                    }
                }

                String[] paths = extractPaths(album);
                scanFilesAndNotify(context, paths, "Album cover updated", onComplete);
            } catch (Exception e) {
                handleMetadataError(context, "Failed to update cover", e);
            }
        }).start();
    }

    /**
     * Deletes physical files for all songs in an album and requests MediaScanner removal.
     *
     * @param context Application context.
     * @param album Target album to delete.
     * @param onComplete Callback invoked after rescan.
     */
    public static void deleteAlbum(Context context, Album album, Runnable onComplete) {
        new Thread(() -> {
            String[] paths = extractPaths(album);
            for (Song song : album.songs) {
                File file = new File(song.path);
                if (file.exists()) {
                    file.delete();
                }
            }
            scanFilesAndNotify(context, paths, "Album deleted", onComplete);
        }).start();
    }

    /**
     * Triggers media scanner indexing on target file paths and dispatches toast feedback.
     *
     * @param context Application context.
     * @param paths File paths to index.
     * @param successMessage Toast text upon full scan completion.
     * @param onComplete Post-scan runnable callback.
     */
    public static void scanFilesAndNotify(Context context, String[] paths, String successMessage, Runnable onComplete) {
        AtomicInteger count = new AtomicInteger(paths.length);
        MediaScannerConnection.scanFile(context, paths, null, (path, uri) -> {
            if (count.decrementAndGet() == 0) {
                mainHandler.post(() -> {
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });
    }

    /**
     * Dispatches an error message toast to the user interface thread.
     *
     * @param context Application context.
     * @param prefix Error descriptor prefix.
     * @param exception Triggering exception.
     */
    private static void handleMetadataError(Context context, String prefix, Exception exception) {
        mainHandler.post(() -> Toast.makeText(context, prefix + ": " + exception.getMessage(), Toast.LENGTH_LONG).show());
    }

    /**
     * Extracts an array of string file paths from all songs in an album model.
     *
     * @param album Source album model.
     * @return Array of absolute path strings.
     */
    private static String[] extractPaths(Album album) {
        String[] paths = new String[album.songs.size()];
        for (int i = 0; i < album.songs.size(); i++) {
            paths[i] = album.songs.get(i).path;
        }
        return paths;
    }

    /**
     * Reads all bytes from an input content URI into a byte array buffer.
     *
     * @param context Application context.
     * @param uri Target source URI.
     * @return Byte array, or null if reading fails.
     */
    private static byte[] readUriBytes(Context context, Uri uri) {
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] data = new byte[16384];
            int nRead;
            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            return buffer.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }
}
