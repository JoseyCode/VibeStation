package com.boogie.vibestation.util

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for PlaylistCoverUtil's backup embedding, restore extraction, and orphan cleanup. Base64, Uri,
 * and ArtUtil are mocked; files are written to a real temp directory and org.json is the real library.
 */
class PlaylistCoverUtilTest {

    private val resolver = mockk<ContentResolver>()
    private lateinit var coversDir: File

    @BeforeTest
    fun setUp() {
        coversDir = File(Files.createTempDirectory("covers").toFile(), "playlist_covers")
        mockkStatic(Base64::class, Uri::class)
        mockkObject(ArtUtil)
        every { Uri.parse(any()) } answers { mockk { every { this@mockk.toString() } returns firstArg<String>() } }
        every { Uri.fromFile(any()) } answers {
            val path = firstArg<File>().absolutePath
            mockk { every { this@mockk.toString() } returns "file://$path" }
        }
    }

    @AfterTest
    fun tearDown() {
        coversDir.parentFile.deleteRecursively()
        unmockkAll()
    }

    private fun playlists(vararg entries: JSONObject) = JSONArray().apply { entries.forEach { put(it) } }

    private fun playlist(name: String, imageUri: String? = null, cover: String? = null) =
        JSONObject().put("name", name).apply {
            if (imageUri != null) put("imageUri", imageUri)
            if (cover != null) put("cover_b64", cover)
        }

    // embedCovers

    /**
     * Verifies a readable cover is embedded under cover_b64 and the original imageUri is kept.
     */
    @Test
    fun embedCoversAddsEncodedCoverAndKeepsImageUri() {
        every { ArtUtil.getBase64Image(resolver, any()) } returns "ENCODED"
        val backup = playlists(playlist("A", imageUri = "content://media/image%3A1"))

        PlaylistCoverUtil.embedCovers(resolver, backup)

        val embedded = backup.getJSONObject(0)
        assertEquals("ENCODED", embedded.getString("cover_b64"))
        assertEquals("content://media/image%3A1", embedded.getString("imageUri"))
    }

    /**
     * Verifies playlists with no cover, or a cover that cannot be read, get no cover_b64 key and
     * that a blank imageUri is never opened.
     */
    @Test
    fun embedCoversSkipsMissingAndUnreadableCovers() {
        every { ArtUtil.getBase64Image(resolver, any()) } returns ""
        val backup = playlists(
            playlist("NoCover"),
            playlist("Blank", imageUri = "  "),
            playlist("Revoked", imageUri = "content://media/image%3A2")
        )

        PlaylistCoverUtil.embedCovers(resolver, backup)

        for (i in 0 until backup.length()) {
            assertFalse(backup.getJSONObject(i).has("cover_b64"))
        }
        verify(exactly = 1) { ArtUtil.getBase64Image(any(), any()) }
    }

    // extractCovers

    /**
     * Verifies the payload is written to the covers directory, imageUri is re-pointed at that file,
     * and cover_b64 is removed so it never reaches SharedPreferences.
     */
    @Test
    fun extractCoversWritesFileAndRepointsImageUri() {
        val bytes = byteArrayOf(1, 2, 3)
        every { Base64.decode("QUJD", Base64.DEFAULT) } returns bytes
        val backup = playlists(
            playlist("A", imageUri = "content://dead/grant", cover = "QUJD")
        )

        PlaylistCoverUtil.extractCovers(backup, coversDir, nowMs = 42L)

        val restored = backup.getJSONObject(0)
        val expected = File(coversDir, "playlist_42_0.jpg")
        assertContentEquals(bytes, expected.readBytes())
        assertEquals("file://${expected.absolutePath}", restored.getString("imageUri"))
        assertFalse(restored.has("cover_b64"))
    }

    /**
     * Verifies a payload that cannot be decoded is dropped but the original imageUri survives, and
     * that the failure does not stop later playlists from being restored.
     */
    @Test
    fun extractCoversKeepsOriginalUriWhenPayloadIsInvalid() {
        every { Base64.decode("!!", Base64.DEFAULT) } throws IllegalArgumentException("bad base64")
        every { Base64.decode("QUJD", Base64.DEFAULT) } returns byteArrayOf(7)
        val backup = playlists(
            playlist("Bad", imageUri = "content://old", cover = "!!"),
            playlist("Good", imageUri = "content://old2", cover = "QUJD")
        )

        PlaylistCoverUtil.extractCovers(backup, coversDir, nowMs = 1L)

        val bad = backup.getJSONObject(0)
        assertEquals("content://old", bad.getString("imageUri"))
        assertFalse(bad.has("cover_b64"))
        assertTrue(backup.getJSONObject(1).getString("imageUri").endsWith("playlist_1_1.jpg"))
    }

    /**
     * Verifies a payload that decodes to zero bytes is treated as a failure rather than writing an empty file.
     */
    @Test
    fun extractCoversRejectsEmptyDecodedPayload() {
        every { Base64.decode("QQ==", Base64.DEFAULT) } returns ByteArray(0)
        val backup = playlists(playlist("A", imageUri = "content://old", cover = "QQ=="))

        PlaylistCoverUtil.extractCovers(backup, coversDir, nowMs = 1L)

        assertEquals("content://old", backup.getJSONObject(0).getString("imageUri"))
        assertFalse(File(coversDir, "playlist_1_0.jpg").exists())
    }

    /**
     * Verifies playlists without an embedded cover, such as backups from older versions, are untouched.
     */
    @Test
    fun extractCoversLeavesPlaylistsWithoutCoverUntouched() {
        val backup = playlists(playlist("A", imageUri = "content://old"), playlist("B"))

        PlaylistCoverUtil.extractCovers(backup, coversDir, nowMs = 1L)

        assertEquals("content://old", backup.getJSONObject(0).getString("imageUri"))
        assertFalse(backup.getJSONObject(1).has("imageUri"))
        assertFalse(coversDir.exists())
    }

    // imageUris

    /**
     * Verifies only non-blank imageUri values are listed, in array order.
     */
    @Test
    fun imageUrisListsNonBlankValues() {
        val backup = playlists(playlist("A", imageUri = "u1"), playlist("B"), playlist("C", imageUri = ""), playlist("D", imageUri = "u2"))

        assertEquals(listOf("u1", "u2"), PlaylistCoverUtil.imageUris(backup))
    }

    // deleteOrphans

    /**
     * Verifies cover files still referenced survive and unreferenced ones are deleted, matching by file
     * name, and that URIs outside the covers folder never protect a same-named file.
     */
    @Test
    fun deleteOrphansKeepsOnlyReferencedCovers() {
        coversDir.mkdirs()
        val kept = File(coversDir, "playlist_1_0.jpg").apply { writeBytes(byteArrayOf(1)) }
        val orphan = File(coversDir, "playlist_1_1.jpg").apply { writeBytes(byteArrayOf(1)) }
        val lookalike = File(coversDir, "playlist_1_2.jpg").apply { writeBytes(byteArrayOf(1)) }

        PlaylistCoverUtil.deleteOrphans(
            coversDir,
            listOf(
                "file://${coversDir.absolutePath}/playlist_1_0.jpg",
                "content://elsewhere/playlist_1_2.jpg"
            )
        )

        assertTrue(kept.exists())
        assertFalse(orphan.exists())
        assertFalse(lookalike.exists())
    }

    /**
     * Verifies cleanup is a no-op when nothing has been restored yet and the folder does not exist.
     */
    @Test
    fun deleteOrphansIgnoresMissingDirectory() {
        PlaylistCoverUtil.deleteOrphans(coversDir, emptyList())

        assertFalse(coversDir.exists())
    }
}
