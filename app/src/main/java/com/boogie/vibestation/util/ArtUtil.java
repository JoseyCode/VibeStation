package com.boogie.vibestation.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility class providing decoding, persistent gallery saving, and serialization helpers for audio artwork.
 */
public final class ArtUtil {

    private ArtUtil() {
        // Prevent instantiation
    }

    /**
     * Decodes artwork bitmap from a content URI, base64 data URI, or physical file embedded ID3 tag.
     *
     * @param resolver ContentResolver instance for URI stream retrieval.
     * @param artworkPath File path or URI string pointing to artwork source.
     * @param isUri Whether the source path represents an Android URI or base64 data scheme.
     * @param qualityMode Subsampling factor used for BitmapFactory decode options.
     * @return Decoded bitmap instance, or null if retrieval fails.
     */
    public static Bitmap decodeArtworkBitmap(ContentResolver resolver, String artworkPath, boolean isUri, int qualityMode) {
        if (artworkPath == null || artworkPath.isEmpty()) {
            return null;
        }
        try {
            if (isUri) {
                if (artworkPath.startsWith("data:image/")) {
                    int commaIndex = artworkPath.indexOf(",");
                    if (commaIndex != -1) {
                        String base64Data = artworkPath.substring(commaIndex + 1);
                        byte[] decodedBytes = Base64.decode(base64Data, Base64.DEFAULT);
                        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                        decodeOptions.inSampleSize = qualityMode;
                        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length, decodeOptions);
                    }
                } else {
                    try (InputStream inputStream = resolver.openInputStream(Uri.parse(artworkPath))) {
                        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                        decodeOptions.inSampleSize = qualityMode;
                        return BitmapFactory.decodeStream(inputStream, null, decodeOptions);
                    }
                }
            } else {
                MediaMetadataRetriever retriever = null;
                try {
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(artworkPath);
                    byte[] rawPictureData = retriever.getEmbeddedPicture();
                    if (rawPictureData != null) {
                        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                        decodeOptions.inSampleSize = qualityMode;
                        return BitmapFactory.decodeByteArray(rawPictureData, 0, rawPictureData.length, decodeOptions);
                    }
                } finally {
                    if (retriever != null) {
                        try { retriever.release(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Writes bitmap image to the system Pictures/VibeStation media store directory.
     *
     * @param resolver ContentResolver instance for inserting media store records.
     * @param bitmap Bitmap to persist.
     * @param title Title used for naming the image file.
     * @return True if saved successfully, false otherwise.
     */
    public static boolean saveBitmapToGallery(ContentResolver resolver, Bitmap bitmap, String title) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, title.replaceAll("[^a-zA-Z0-9.-]", "_") + "_cover.jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VibeStation");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    if (out != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                }
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Reads image file content from a provider, scales it to 400x400 constraints, and converts to Base64.
     *
     * @param resolver ContentResolver instance.
     * @param uri Provider path pointing to the selected image.
     * @return Base64 encoded string, or empty string on failure.
     */
    public static String getBase64Image(ContentResolver resolver, Uri uri) {
        try {
            InputStream inputStream = resolver.openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            if (bitmap == null) return "";
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, 400, 400, true);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream);
            return Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.DEFAULT);
        } catch (Exception e) {
            return "";
        }
    }
}
