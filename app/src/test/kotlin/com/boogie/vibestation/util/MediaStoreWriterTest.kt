package com.boogie.vibestation.util

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Verifies [MediaStoreWriter] publishes complete tracks and never leaves a partial record behind. */
class MediaStoreWriterTest {

    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()
    private val track = NewTrack("a.mp3", "A", "Artist", "Album", "audio/mpeg", "Music/")

    @Before
    fun setUp() {
        mockkConstructor(ContentValues::class)
        every { anyConstructed<ContentValues>().put(any<String>(), any<String>()) } just Runs
        every { anyConstructed<ContentValues>().put(any<String>(), any<Int>()) } just Runs
        every { anyConstructed<ContentValues>().clear() } just Runs
        mockkStatic(ContentUris::class)
        every { ContentUris.parseId(uri) } returns 42L
        every { resolver.insert(any(), any()) } returns uri
        every { resolver.delete(any(), any(), any()) } returns 1
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** The bytes handed to the write callback land in the output stream and the new id is returned. */
    @Test
    fun successWritesBytesAndReturnsId() {
        val sink = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri) } returns sink

        val id = MediaStoreWriter.insertTrack(resolver, track) { it.write(byteArrayOf(1, 2, 3)) }

        assertEquals(42L, id)
        assertEquals(listOf<Byte>(1, 2, 3), sink.toByteArray().toList())
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    /** A failing write deletes the half-created record and rethrows the original error. */
    @Test
    fun writeFailureDeletesRecord() {
        every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()

        assertFailsWith<IOException> {
            MediaStoreWriter.insertTrack(resolver, track) { throw IOException("boom") }
        }

        verify(exactly = 1) { resolver.delete(uri, null, null) }
    }

    /** No output stream available also removes the record instead of leaving it empty. */
    @Test
    fun missingOutputStreamDeletesRecord() {
        every { resolver.openOutputStream(uri) } returns null

        assertFailsWith<IOException> { MediaStoreWriter.insertTrack(resolver, track) { } }

        verify(exactly = 1) { resolver.delete(uri, null, null) }
    }

    /** When MediaStore refuses the insert there is nothing to clean up. */
    @Test
    fun insertFailureThrowsWithoutDelete() {
        every { resolver.insert(any(), any()) } returns null

        assertFailsWith<IOException> { MediaStoreWriter.insertTrack(resolver, track) { } }

        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }
}
