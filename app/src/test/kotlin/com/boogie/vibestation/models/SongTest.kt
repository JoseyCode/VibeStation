package com.boogie.vibestation.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Contract tests for Song model data invariants.
 */
class SongTest {

    private fun song(
        id: String = "track-42",
        path: String? = "/storage/emulated/0/Music/get_lucky.mp3",
        albumId: String = "alb-99"
    ) = Song(id, "Get Lucky", "Daft Punk", path, albumId, "Random Access Memories", 3, 1690000000L)

    /**
     * Verifies Song constructor accurately maps all metadata attributes.
     */
    @Test
    fun constructorInitialization() {
        val song = song()

        assertEquals("track-42", song.id)
        assertEquals("Get Lucky", song.title)
        assertEquals("Daft Punk", song.artist)
        assertEquals("/storage/emulated/0/Music/get_lucky.mp3", song.path)
        assertEquals("alb-99", song.albumId)
        assertEquals("Random Access Memories", song.album)
        assertEquals(3, song.trackNumber)
        assertEquals(1690000000L, song.dateAdded)
    }

    /**
     * Verifies album art URI generation format contract.
     */
    @Test
    fun albumArtUriString() {
        assertEquals("content://media/external/audio/albumart/alb-99", song().albumArtUriString)
    }

    /**
     * Verifies equality and hash code contracts across identical and differing song instances.
     */
    @Test
    fun equalsAndHashCode() {
        val song1 = Song("t-1", "Song A", "Artist A", "/path/a.mp3", "alb-1", "Album A", 1, 100L)
        val song2 = Song("t-1", "Song A", "Artist A", "/path/a.mp3", "alb-1", "Album A", 1, 100L)
        val song3 = Song("t-2", "Song B", "Artist B", "/path/b.mp3", "alb-2", "Album B", 2, 200L)

        assertEquals(song1, song2)
        assertEquals(song1.hashCode(), song2.hashCode())
        assertNotEquals(song1, song3)
        assertNotEquals<Any?>(song1, null)
    }

    /**
     * Verifies identity is track ID plus path only: display metadata is ignored, while a differing path
     * (same ID, different file) is a different track.
     */
    @Test
    fun identityIgnoresDisplayMetadataButNotPath() {
        val original = song()
        val retitled = Song("track-42", "Renamed", "Someone Else", original.path, "alb-1", "Other", 9, 5L)

        assertEquals(original, retitled)
        assertNotEquals(original, song(path = "/storage/emulated/0/Music/copy.mp3"))
        assertNotEquals(original, song(path = null))
    }
}
