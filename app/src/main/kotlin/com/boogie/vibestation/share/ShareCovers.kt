package com.boogie.vibestation.share

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.boogie.vibestation.util.PlaylistCoverUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Moves a playlist cover between a local image and the Base64 text carried in a [ShareManifest]. Unlike
 * playlist backups, which squash covers to a small square, a share keeps the picture as it is: the original
 * file when it is a sensible size, otherwise a high-quality copy scaled down with its aspect ratio intact.
 * Receiving reuses the backup restore path ([PlaylistCoverUtil]).
 */
internal object ShareCovers {

    /** Source files larger than this are not even read, so a stray huge file cannot exhaust memory. */
    private const val MAX_SOURCE_BYTES = 40 * 1024 * 1024
    private const val JPEG_QUALITY = 90
    private const val READ_BUFFER = 16 * 1024
    private const val IMAGE_URI_KEY = "imageUri"
    private const val COVER_KEY = "cover_b64"

    /**
     * Encodes a playlist's cover for sending.
     *
     * @param resolver Resolver used to read the image.
     * @param imageUri The playlist's cover URI, or null.
     * @return Base64 image (no line breaks), or an empty string when there is no readable cover.
     */
    fun encode(resolver: ContentResolver, imageUri: String?): String {
        if (imageUri.isNullOrBlank()) return ""
        val prepared = readCover(resolver, imageUri)?.let(::prepare)
        return if (prepared == null) "" else Base64.encodeToString(prepared, Base64.NO_WRAP)
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

    /**
     * Decodes a received cover for showing on screen, no larger than needed.
     *
     * @param encoded Base64 cover from a manifest.
     * @param maxSide Longest side wanted, in pixels; the result is at least this big unless the image is smaller.
     * @return The bitmap, or null when [encoded] is not a readable image.
     */
    fun decode(encoded: String, maxSide: Int): Bitmap? {
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (ignored: IllegalArgumentException) {
            null
        }
        return bytes?.let { decodeSampled(it, maxSide) }
    }

    /** Reads the cover file, or null when it is gone, unreadable, or too big to bother with. */
    private fun readCover(resolver: ContentResolver, imageUri: String): ByteArray? = try {
        resolver.openInputStream(Uri.parse(imageUri))?.use { readBounded(it) }
    } catch (ignored: IOException) {
        null
    } catch (ignored: SecurityException) {
        null // the read grant for this cover was revoked
    }

    /** The picture's size without decoding it, or null when the bytes are not an image. */
    private fun boundsOf(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else bounds.outWidth to bounds.outHeight
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val (width, height) = boundsOf(bytes) ?: return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverSizing.sampleSize(width, height, maxSide)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** The bytes to send for a source image: the original when it is small enough, else a scaled JPEG; null if undecodable. */
    private fun prepare(source: ByteArray): ByteArray? {
        val (width, height) = boundsOf(source) ?: return null
        return if (CoverSizing.keepsOriginal(source.size, width, height)) source else scaledJpeg(source, width, height)
    }

    private fun scaledJpeg(source: ByteArray, width: Int, height: Int): ByteArray? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverSizing.sampleSize(width, height, CoverSizing.MAX_SIDE_PX)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, options) ?: return null
        val (fitWidth, fitHeight) = CoverSizing.fit(decoded.width, decoded.height, CoverSizing.MAX_SIDE_PX)
        val scaled = if (fitWidth == decoded.width && fitHeight == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, fitWidth, fitHeight, true).also { decoded.recycle() }
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        scaled.recycle()
        return output.toByteArray()
    }

    /** Reads the whole stream, giving up with an empty result if it is longer than [MAX_SOURCE_BYTES]. */
    private fun readBounded(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > MAX_SOURCE_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/** The pure size decisions behind [ShareCovers]: when a cover is sent as it is, and how a bigger one is shrunk. */
internal object CoverSizing {

    /** Largest cover file sent byte for byte. */
    const val ORIGINAL_LIMIT_BYTES = 3 * 1024 * 1024

    /** Longest side, in pixels, of a cover that has to be scaled down. */
    const val MAX_SIDE_PX = 2048

    /** Longest side an untouched cover may have; a small file can still be a huge, heavily compressed picture. */
    private const val ORIGINAL_MAX_SIDE_PX = 4096

    /**
     * Whether a cover can be sent exactly as it is on disk.
     *
     * @param sizeBytes File size.
     * @param width     Picture width in pixels.
     * @param height    Picture height in pixels.
     * @return True when both the file and the picture are within the limits.
     */
    fun keepsOriginal(sizeBytes: Int, width: Int, height: Int): Boolean =
        sizeBytes <= ORIGINAL_LIMIT_BYTES && maxOf(width, height) <= ORIGINAL_MAX_SIDE_PX

    /**
     * Picks the decoder's power-of-two shrink factor: the largest one that still leaves the longest side
     * at or above [maxSide], so decoding a big photo never needs its full-size bitmap in memory.
     *
     * @param width   Picture width in pixels.
     * @param height  Picture height in pixels.
     * @param maxSide Longest side wanted.
     * @return A factor of 1 or more (1, 2, 4, ...).
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /**
     * Scales a size down so its longest side is at most [maxSide], keeping the aspect ratio.
     *
     * @param width   Picture width in pixels.
     * @param height  Picture height in pixels.
     * @param maxSide Longest side allowed.
     * @return The new width and height, each at least 1; the input unchanged if it already fits.
     */
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toFloat() / longest
        return maxOf(1, Math.round(width * scale)) to maxOf(1, Math.round(height * scale))
    }
}
