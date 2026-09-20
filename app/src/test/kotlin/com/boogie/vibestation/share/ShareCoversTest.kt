package com.boogie.vibestation.share

import android.content.ContentResolver
import com.boogie.vibestation.util.PlaylistCoverUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for [ShareCovers], with [PlaylistCoverUtil] mocked; it has its own tests. */
class ShareCoversTest {

    private val resolver = mockk<ContentResolver>()
    private val dir = File("covers")

    @BeforeTest
    fun setUp() {
        mockkObject(PlaylistCoverUtil)
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    /** The cover is read from the playlist's imageUri and returned as Base64. */
    @Test
    fun encodeReturnsEmbeddedCover() {
        every { PlaylistCoverUtil.embedCovers(resolver, any()) } answers {
            val entry = secondArg<JSONArray>().getJSONObject(0)
            assertEquals("content://cover/1", entry.getString("imageUri"))
            entry.put("cover_b64", "QUJD")
        }

        assertEquals("QUJD", ShareCovers.encode(resolver, "content://cover/1"))
    }

    /** No cover, or one that cannot be read, yields an empty string without touching the resolver when blank. */
    @Test
    fun encodeWithoutCover() {
        every { PlaylistCoverUtil.embedCovers(resolver, any()) } returns Unit

        assertEquals("", ShareCovers.encode(resolver, null))
        assertEquals("", ShareCovers.encode(resolver, " "))
        verify(exactly = 0) { PlaylistCoverUtil.embedCovers(any(), any()) }
        assertEquals("", ShareCovers.encode(resolver, "content://gone"))
    }

    /** A stored cover comes back as the local URI PlaylistCoverUtil wrote. */
    @Test
    fun storeReturnsLocalUri() {
        every { PlaylistCoverUtil.extractCovers(any(), dir, 7L) } answers {
            val entry = firstArg<JSONArray>().getJSONObject(0)
            assertEquals("QUJD", entry.getString("cover_b64"))
            entry.remove("cover_b64")
            entry.put("imageUri", "file:///covers/playlist_7_0.jpg")
        }

        assertEquals("file:///covers/playlist_7_0.jpg", ShareCovers.store("QUJD", dir, 7L))
    }

    /** Nothing to store, or a cover that fails to write, gives null. */
    @Test
    fun storeReturnsNullWhenNothingWritten() {
        every { PlaylistCoverUtil.extractCovers(any(), any(), any()) } answers {
            firstArg<JSONArray>().getJSONObject(0).remove("cover_b64")
        }

        assertNull(ShareCovers.store("", dir, 7L))
        assertNull(ShareCovers.store("QUJD", dir, 7L))
    }
}
