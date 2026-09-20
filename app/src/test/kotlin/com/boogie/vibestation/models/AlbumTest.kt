package com.boogie.vibestation.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Contract tests for Album model invariants and state management.
 */
class AlbumTest {

    /**
     * Verifies Album constructor accurately binds metadata fields.
     */
    @Test
    fun constructorInitialization() {
        val album = Album("alb-1", "Discovery", "Daft Punk", 1700000000L)

        assertEquals("alb-1", album.albumId)
        assertEquals("Discovery", album.name)
        assertEquals("Daft Punk", album.artist)
        assertEquals(1700000000L, album.dateAdded)
    }

    /**
     * Verifies initial default values for non-constructor mutable state.
     */
    @Test
    fun defaultState() {
        val album = Album("alb-2", "Random Access Memories", "Daft Punk", 1700000000L)

        assertFalse(album.isFire)
        assertTrue(album.songs.isEmpty())
    }

    /**
     * Verifies songs collection mutation within an Album instance.
     */
    @Test
    fun songCollectionOperations() {
        val album = Album("alb-3", "Homework", "Daft Punk", 1700000000L)
        val song = Song("s1", "Around the World", "Daft Punk", "/path/song.mp3", "alb-3", "Homework", 1, 1700000000L)

        album.songs.add(song)
        assertEquals(1, album.songs.size)
        assertEquals("s1", album.songs[0].id)

        album.isFire = true
        assertTrue(album.isFire)
    }

    /**
     * Verifies equality and hash code contracts across identical and differing album instances.
     */
    @Test
    fun equalsAndHashCode() {
        val album1 = Album("alb-1", "Discovery", "Daft Punk", 1700000000L)
        val album2 = Album("alb-1", "Discovery", "Daft Punk", 1700000000L)
        val album3 = Album("alb-2", "Homework", "Daft Punk", 1700000000L)

        assertEquals(album1, album2)
        assertEquals(album1.hashCode(), album2.hashCode())
        assertNotEquals(album1, album3)
        assertNotEquals<Any?>(album1, null)
    }
}
