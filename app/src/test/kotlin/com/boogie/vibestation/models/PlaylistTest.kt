package com.boogie.vibestation.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for Playlist model invariants and state management.
 */
class PlaylistTest {

    /**
     * Verifies Playlist constructor initializes fields and defaults correctly.
     */
    @Test
    fun constructorInitialization() {
        val playlist = Playlist("Chill Vibes", "content://media/images/1")

        assertEquals("Chill Vibes", playlist.name)
        assertEquals("content://media/images/1", playlist.imageUri)
        assertEquals("", playlist.description)
        assertFalse(playlist.isFire)
        assertTrue(playlist.songs.isEmpty())
    }

    /**
     * Verifies Playlist supports nullable image URI during creation.
     */
    @Test
    fun nullImageUriInitialization() {
        val playlist = Playlist("Favorites", null)

        assertEquals("Favorites", playlist.name)
        assertNull(playlist.imageUri)
    }

    /**
     * Verifies mutable field updates and songs list operations.
     */
    @Test
    fun mutationsAndSongCollectionOperations() {
        val playlist = Playlist("Gym", null)
        val song = Song("s1", "One More Time", "Daft Punk", "/music/song.mp3", "alb-1", "Discovery", 1, 1700000000L)

        playlist.name = "Gym Heavy"
        playlist.description = "Workout set"
        playlist.imageUri = "content://media/images/2"
        playlist.isFire = true
        playlist.songs.add(song)

        assertEquals("Gym Heavy", playlist.name)
        assertEquals("Workout set", playlist.description)
        assertEquals("content://media/images/2", playlist.imageUri)
        assertTrue(playlist.isFire)
        assertEquals(1, playlist.songs.size)
        assertEquals("s1", playlist.songs[0].id)
    }

    /**
     * Verifies equality and hash code contracts across identical and differing playlist instances.
     */
    @Test
    fun equalsAndHashCode() {
        val playlist1 = Playlist("Vibes", null)
        val playlist2 = Playlist("Vibes", "content://art/1")
        val playlist3 = Playlist("Other", null)

        assertEquals(playlist1, playlist2)
        assertEquals(playlist1.hashCode(), playlist2.hashCode())
        assertNotEquals(playlist1, playlist3)
        assertNotEquals<Any?>(playlist1, null)
    }
}
