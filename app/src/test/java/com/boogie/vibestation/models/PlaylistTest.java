package com.boogie.vibestation.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for Playlist model invariants and state management.
 */
public class PlaylistTest {

    /**
     * Verifies Playlist constructor initializes fields and defaults correctly.
     */
    @Test
    public void testConstructorInitialization() {
        Playlist playlist = new Playlist("Chill Vibes", "content://media/images/1");

        assertEquals("Chill Vibes", playlist.name);
        assertEquals("content://media/images/1", playlist.imageUri);
        assertEquals("", playlist.description);
        assertFalse(playlist.isFire);
        assertNotNull(playlist.songs);
        assertTrue(playlist.songs.isEmpty());
    }

    /**
     * Verifies Playlist supports nullable image URI during creation.
     */
    @Test
    public void testNullImageUriInitialization() {
        Playlist playlist = new Playlist("Favorites", null);

        assertEquals("Favorites", playlist.name);
        assertEquals(null, playlist.imageUri);
    }

    /**
     * Verifies mutable field updates and songs list operations.
     */
    @Test
    public void testMutationsAndSongCollectionOperations() {
        Playlist playlist = new Playlist("Gym", null);
        Song song = new Song("s1", "One More Time", "Daft Punk", "/music/song.mp3", "alb-1", "Discovery", 1, 1700000000L);

        playlist.name = "Gym Heavy";
        playlist.description = "Workout set";
        playlist.imageUri = "content://media/images/2";
        playlist.isFire = true;
        playlist.songs.add(song);

        assertEquals("Gym Heavy", playlist.name);
        assertEquals("Workout set", playlist.description);
        assertEquals("content://media/images/2", playlist.imageUri);
        assertTrue(playlist.isFire);
        assertEquals(1, playlist.songs.size());
        assertEquals("s1", playlist.songs.get(0).id);
    }
}
