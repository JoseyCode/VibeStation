package com.boogie.vibestation.util

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Decoding, persistent gallery saving, and serialization helpers for audio artwork.
 */
object ArtUtil {

    private const val BASE64_IMAGE_SIZE_PX = 400
    private const val BASE64_JPEG_QUALITY = 70

    /**
     * Decodes artwork bitmap from a content URI, base64 data URI, or physical file embedded ID3 tag.
     *
     * @param resolver ContentResolver instance for URI stream retrieval.
     * @param artworkPath File path or URI string pointing to artwork source.
     * @param isUri Whether the source path represents an Android URI or base64 data scheme.
     * @param qualityMode Subsampling factor used for BitmapFactory decode options.
     * @return Decoded bitmap instance, or null if retrieval fails.
     */
    fun decodeArtworkBitmap(resolver: ContentResolver, artworkPath: String?, isUri: Boolean, qualityMode: Int): Bitmap? {
        if (artworkPath.isNullOrEmpty()) return null
        return try {
            when {
                isUri && artworkPath.startsWith("data:image/") -> decodeDataUri(artworkPath, qualityMode)
                isUri -> resolver.openInputStream(Uri.parse(artworkPath)).use {
                    BitmapFactory.decodeStream(it, null, sampledOptions(qualityMode))
                }
                else -> decodeEmbeddedPicture(artworkPath, qualityMode)
            }
        } catch (ignored: Exception) {
            null
        }
    }

    /** Decodes the base64 payload of a `data:image/...;base64,` URI, or null if it has no payload. */
    private fun decodeDataUri(dataUri: String, qualityMode: Int): Bitmap? {
        val commaIndex = dataUri.indexOf(',')
        if (commaIndex == -1) return null
        val decodedBytes = Base64.decode(dataUri.substring(commaIndex + 1), Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size, sampledOptions(qualityMode))
    }

    /** Decodes the ID3-embedded picture from an audio file, or null if it has none. */
    private fun decodeEmbeddedPicture(filePath: String, qualityMode: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val rawPictureData = retriever.embeddedPicture ?: return null
            return BitmapFactory.decodeByteArray(rawPictureData, 0, rawPictureData.size, sampledOptions(qualityMode))
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun sampledOptions(qualityMode: Int) = BitmapFactory.Options().apply { inSampleSize = qualityMode }

    /**
     * Writes bitmap image to the system Pictures/VibeStation media store directory.
     *
     * @param resolver ContentResolver instance for inserting media store records.
     * @param bitmap Bitmap to persist.
     * @param title Title used for naming the image file.
     * @return True if saved successfully, false otherwise.
     */
    fun saveBitmapToGallery(resolver: ContentResolver, bitmap: Bitmap, title: String): Boolean {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, title.replace(Regex("[^a-zA-Z0-9.-]"), "_") + "_cover.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VibeStation")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri).use { out ->
                if (out != null) bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return true
        } catch (ignored: Exception) {
            return false
        }
    }

    /**
     * Reads image file content from a provider, scales it to 400x400 constraints, and converts to Base64.
     *
     * @param resolver ContentResolver instance.
     * @param uri Provider path pointing to the selected image.
     * @return Base64 encoded string, or empty string on failure.
     */
    fun getBase64Image(resolver: ContentResolver?, uri: Uri?): String {
        if (resolver == null || uri == null) return ""
        return try {
            resolver.openInputStream(uri).use { inputStream ->
                if (inputStream == null) return ""
                val bitmap = BitmapFactory.decodeStream(inputStream) ?: return ""
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, BASE64_IMAGE_SIZE_PX, BASE64_IMAGE_SIZE_PX, true)
                if (scaledBitmap !== bitmap) bitmap.recycle()
                val output = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, BASE64_JPEG_QUALITY, output)
                Base64.encodeToString(output.toByteArray(), Base64.DEFAULT)
            }
        } catch (e: Exception) {
            ""
        }
    }
}
