package com.boogie.vibestation.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.OutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests for ArtUtil decoding and Base64 serialization. Android graphics and codec statics are mocked,
 * so these pin ArtUtil's own decisions (branching, sampling, sizing, quality, recycling), not pixels.
 * Embedded-ID3 extraction and gallery saving depend on MediaMetadataRetriever/MediaStore and are not covered.
 */
class ArtUtilTest {

    private val resolver = mockk<ContentResolver>()
    private val decoded = mockk<Bitmap>()

    @BeforeTest
    fun setUp() {
        mockkStatic(BitmapFactory::class, Bitmap::class, Base64::class, Uri::class)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // decodeArtworkBitmap

    /**
     * Verifies a null or empty source short-circuits to null without touching any decoder.
     */
    @Test
    fun decodeReturnsNullForMissingSource() {
        assertNull(ArtUtil.decodeArtworkBitmap(resolver, null, isUri = true, qualityMode = 1))
        assertNull(ArtUtil.decodeArtworkBitmap(resolver, "", isUri = false, qualityMode = 1))
        verify(exactly = 0) { BitmapFactory.decodeByteArray(any(), any(), any(), any()) }
    }

    /**
     * Verifies a data URI is decoded from the payload after the first comma, with the requested sampling.
     */
    @Test
    fun decodeDataUriUsesPayloadAfterCommaAndQualityMode() {
        val payload = byteArrayOf(9, 8, 7)
        val options = slot<BitmapFactory.Options>()
        every { Base64.decode("QUJD", Base64.DEFAULT) } returns payload
        every { BitmapFactory.decodeByteArray(payload, 0, 3, capture(options)) } returns decoded

        val bitmap = ArtUtil.decodeArtworkBitmap(resolver, "data:image/png;base64,QUJD", isUri = true, qualityMode = 4)

        assertSame(decoded, bitmap)
        assertEquals(4, options.captured.inSampleSize)
    }

    /**
     * Verifies a data URI with no comma has no payload and yields null.
     */
    @Test
    fun decodeDataUriWithoutPayloadReturnsNull() {
        assertNull(ArtUtil.decodeArtworkBitmap(resolver, "data:image/png;base64", isUri = true, qualityMode = 1))
    }

    /**
     * Verifies a content URI is opened through the resolver and decoded with the requested sampling.
     */
    @Test
    fun decodeContentUriStreamsThroughResolver() {
        val uri = mockk<Uri>()
        val stream = ByteArrayInputStream(byteArrayOf(1))
        val options = slot<BitmapFactory.Options>()
        every { Uri.parse("content://art/1") } returns uri
        every { resolver.openInputStream(uri) } returns stream
        every { BitmapFactory.decodeStream(stream, null, capture(options)) } returns decoded

        val bitmap = ArtUtil.decodeArtworkBitmap(resolver, "content://art/1", isUri = true, qualityMode = 2)

        assertSame(decoded, bitmap)
        assertEquals(2, options.captured.inSampleSize)
    }

    /**
     * Verifies any decoding failure is swallowed and reported as null rather than crashing the caller.
     */
    @Test
    fun decodeSwallowsFailures() {
        every { Uri.parse(any()) } throws IllegalArgumentException("bad uri")

        assertNull(ArtUtil.decodeArtworkBitmap(resolver, "content://broken", isUri = true, qualityMode = 1))
    }

    // getBase64Image

    /**
     * Verifies a missing resolver or URI yields an empty string.
     */
    @Test
    fun base64ReturnsEmptyForNullInputs() {
        assertEquals("", ArtUtil.getBase64Image(null, mockk()))
        assertEquals("", ArtUtil.getBase64Image(resolver, null))
    }

    /**
     * Verifies an unopenable stream, an undecodable image, and a throwing resolver all yield an empty string.
     */
    @Test
    fun base64ReturnsEmptyOnFailures() {
        val uri = mockk<Uri>()
        every { resolver.openInputStream(uri) } returns null
        assertEquals("", ArtUtil.getBase64Image(resolver, uri))

        val stream = ByteArrayInputStream(byteArrayOf(1))
        every { resolver.openInputStream(uri) } returns stream
        every { BitmapFactory.decodeStream(stream) } returns null
        assertEquals("", ArtUtil.getBase64Image(resolver, uri))

        every { resolver.openInputStream(uri) } throws SecurityException("revoked")
        assertEquals("", ArtUtil.getBase64Image(resolver, uri))
    }

    private fun stubDecodeAndScale(scaled: Bitmap, original: Bitmap, uri: Uri) {
        val stream = ByteArrayInputStream(byteArrayOf(1))
        every { resolver.openInputStream(uri) } returns stream
        every { BitmapFactory.decodeStream(stream) } returns original
        every { Bitmap.createScaledBitmap(original, 400, 400, true) } returns scaled
        every { scaled.compress(Bitmap.CompressFormat.JPEG, 70, any()) } answers {
            thirdArg<OutputStream>().write(byteArrayOf(1, 2, 3))
            true
        }
        every { Base64.encodeToString(any(), Base64.DEFAULT) } returns "ENCODED"
    }

    /**
     * Verifies the image is scaled to 400x400, compressed as JPEG quality 70, Base64-encoded, and the
     * source bitmap is recycled once a distinct scaled copy exists.
     */
    @Test
    fun base64ScalesCompressesAndRecyclesOriginal() {
        val uri = mockk<Uri>()
        val original = mockk<Bitmap>()
        val scaled = mockk<Bitmap>()
        every { original.recycle() } just runs
        stubDecodeAndScale(scaled, original, uri)

        val result = ArtUtil.getBase64Image(resolver, uri)

        assertEquals("ENCODED", result)
        verify { original.recycle() }
        verify { Base64.encodeToString(match { it.contentEquals(byteArrayOf(1, 2, 3)) }, Base64.DEFAULT) }
    }

    /**
     * Verifies the original is not recycled when scaling returns the same instance (already 400x400).
     */
    @Test
    fun base64DoesNotRecycleWhenScalingReturnsSameBitmap() {
        val uri = mockk<Uri>()
        val original = mockk<Bitmap>()
        stubDecodeAndScale(original, original, uri)

        assertEquals("ENCODED", ArtUtil.getBase64Image(resolver, uri))
        verify(exactly = 0) { original.recycle() }
    }
}
