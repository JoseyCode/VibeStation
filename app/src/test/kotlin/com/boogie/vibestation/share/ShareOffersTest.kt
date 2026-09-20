package com.boogie.vibestation.share

import com.boogie.vibestation.models.Song
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShareOffersTest {
    private fun song(id: String) = Song(id, "Title $id", "Artist", null, "al", "Album", 1, 0)

    private fun file(id: String, size: Long = 10) = ShareFile(id, "$id.mp3", "audio/mpeg", size, 1000)

    private val opened = mutableListOf<String>()

    private fun prepare(
        songs: List<Song>,
        files: Map<String, ShareFile>,
        cover: String = "",
        kind: ShareKind = ShareKind.PLAYLIST
    ) = ShareOffers.prepare(kind, "Mix", "desc", cover, songs, files) { id ->
        opened += id
        ByteArrayInputStream(id.toByteArray())
    }

    @Test
    fun manifestCarriesNameKindAndTracksInOrder() {
        val offer = assertNotNull(prepare(listOf(song("1"), song("2")), mapOf("1" to file("1"), "2" to file("2"))))
        assertEquals("Mix", offer.manifest.name)
        assertEquals(ShareKind.PLAYLIST, offer.manifest.kind)
        assertEquals(listOf("Title 1", "Title 2"), offer.manifest.tracks.map { it.title })
    }

    @Test
    fun indexOpensTheSongThatTrackDescribes() {
        val offer = assertNotNull(prepare(listOf(song("7"), song("8")), mapOf("7" to file("7"), "8" to file("8"))))
        assertEquals("8", offer.open(1).readBytes().decodeToString())
        assertEquals(listOf("8"), opened)
    }

    @Test
    fun unsendableSongsAreSkippedAndIndicesStayAligned() {
        val songs = listOf(song("1"), song("2"), song("3"), song("4"))
        val files = mapOf("1" to file("1"), "3" to file("3", size = 0), "4" to file("4"))
        val offer = assertNotNull(prepare(songs, files))
        assertEquals(listOf("Title 1", "Title 4"), offer.manifest.tracks.map { it.title })
        assertEquals("4", offer.open(1).readBytes().decodeToString())
    }

    @Test
    fun nothingSendableGivesNoOffer() {
        assertNull(prepare(listOf(song("1")), emptyMap()))
        assertNull(prepare(emptyList(), emptyMap()))
    }

    @Test
    fun tracksBeyondTheManifestLimitAreLeftOut() {
        val songs = (1..1001).map { song(it.toString()) }
        val files = songs.associate { it.id to file(it.id) }
        val offer = assertNotNull(prepare(songs, files))
        assertEquals(ShareManifest.MAX_TRACKS, offer.manifest.tracks.size)
        assertEquals("1000", offer.open(999).readBytes().decodeToString())
    }

    @Test
    fun coverTooBigForTheManifestIsDropped() {
        val songs = listOf(song("1"))
        val files = mapOf("1" to file("1"))
        assertEquals("", assertNotNull(prepare(songs, files, cover = "x".repeat(ShareManifest.MAX_COVER_CHARS + 1))).manifest.coverBase64)
        assertEquals("abc", assertNotNull(prepare(songs, files, cover = "abc")).manifest.coverBase64)
    }

    @Test
    fun coverAtExactlyTheLimitIsKept() {
        val cover = "x".repeat(ShareManifest.MAX_COVER_CHARS)
        assertEquals(cover.length, assertNotNull(prepare(listOf(song("1")), mapOf("1" to file("1")), cover)).manifest.coverBase64.length)
    }

    @Test
    fun descriptionIsCarried() {
        assertEquals("desc", assertNotNull(prepare(listOf(song("1")), mapOf("1" to file("1")))).manifest.description)
    }
}
