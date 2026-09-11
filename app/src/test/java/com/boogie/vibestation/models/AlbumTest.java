package com.boogie.vibestation.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for Album model invariants and state management.
 */
public class AlbumTest {

    /**
     * Verifies Album constructor accurately binds metadata fields.
     */
    @Test
    public void testConstructorInitialization() {
        Album album = new Album("alb-1", "Discovery", "Daft Punk", 1700000000L);

        assertEquals("alb-1", album.albumId);
        assertEquals("Discovery", album.name);
        assertEquals("Daft Punk", album.artist);
        assertEquals(1700000000L, album.dateAdded);
    }

    /**
     * Verifies initial default values for non-constructor mutable state.
     */
    @Test
    public void testDefaultState() {
        Album album = new Album("alb-2", "Random Access Memories", "Daft Punk", 1700000000L);

        assertFalse(album.isFire);
        assertNotNull(album.songs);
        assertTrue(album.songs.isEmpty());
    }

    /**
     * Verifies songs collection mutation within an Album instance.
     */
    @Test
    public void testSongCollectionOperations() {
        Album album = new Album("alb-3", "Homework", "Daft Punk", 1700000000L);
        Song song = new Song("s1", "Around the World", "Daft Punk", "/path/song.mp3", "alb-3", "Homework", 1, 1700000000L);

        album.songs.add(song);
        assertEquals(1, album.songs.size());
        assertEquals("s1", album.songs.get(0).id);

        album.isFire = true;
        assertTrue(album.isFire);
    }

    /**
     * Verifies equality and hash code contracts across identical and differing album instances.
     */
    @Test
    public void testEqualsAndHashCode() {
        Album album1 = new Album("alb-1", "Discovery", "Daft Punk", 1700000000L);
        Album album2 = new Album("alb-1", "Discovery", "Daft Punk", 1700000000L);
        Album album3 = new Album("alb-2", "Homework", "Daft Punk", 1700000000L);

        assertEquals(album1, album2);
        assertEquals(album1.hashCode(), album2.hashCode());
        org.junit.Assert.assertNotEquals(album1, album3);
        org.junit.Assert.assertNotEquals(album1, null);
    }
}
