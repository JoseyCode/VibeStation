package com.boogie.vibestation.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the audio format table shared by uploads, downloads and Share Mode. */
class AudioFormatsTest {

    /** Extensions come from the last path segment, lowercase, and are empty when absent. */
    @Test
    fun extensionOfReadsLastSegment() {
        assertEquals("flac", AudioFormats.extensionOf("/Music/A.B/Song.FLAC"))
        assertEquals("", AudioFormats.extensionOf("/Music/archive.d/noext"))
    }

    /** Every format the server stores is supported; others are not. */
    @Test
    fun supportsOnlyServerFormats() {
        for (name in listOf("a.mp3", "a.flac", "a.m4a", "a.aac", "a.ogg", "a.opus", "a.wav")) {
            assertTrue(AudioFormats.isSupported(name), name)
        }
        for (name in listOf("a.wma", "a.amr", "a", "")) {
            assertFalse(AudioFormats.isSupported(name), name)
        }
    }

    /** Upload and download MIME types follow the extension, defaulting to MP3. */
    @Test
    fun mimeTypesFollowExtension() {
        assertEquals("audio/flac", AudioFormats.mimeOf("x.FLAC"))
        assertEquals("audio/mpeg", AudioFormats.mimeOf("x.unknown"))
        assertNull(AudioFormats.mimeForExtension("wma"))
    }

    /** Server-reported formats and MIME types map back to a stored extension, falling back to MP3. */
    @Test
    fun extensionsResolveFromFormatAndMime() {
        assertEquals("flac", AudioFormats.extensionForFormat("FLAC"))
        assertEquals("mp3", AudioFormats.extensionForFormat(""))
        assertEquals("mp3", AudioFormats.extensionForFormat("wma"))
        assertEquals("m4a", AudioFormats.extensionForMime("audio/mp4"))
        assertEquals("mp3", AudioFormats.extensionForMime("video/x"))
    }
}
