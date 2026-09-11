package com.boogie.vibestation.models;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Contract tests for Song model data invariants.
 */
public class SongTest {

    /**
     * Verifies Song constructor accurately maps all metadata attributes.
     */
    @Test
    public void testConstructorInitialization() {
        Song song = new Song(
                "track-42",
                "Get Lucky",
                "Daft Punk",
                "/storage/emulated/0/Music/get_lucky.mp3",
                "alb-99",
                "Random Access Memories",
                3,
                1690000000L
        );

        assertEquals("track-42", song.id);
        assertEquals("Get Lucky", song.title);
        assertEquals("Daft Punk", song.artist);
        assertEquals("/storage/emulated/0/Music/get_lucky.mp3", song.path);
        assertEquals("alb-99", song.albumId);
        assertEquals("Random Access Memories", song.album);
        assertEquals(3, song.trackNumber);
        assertEquals(1690000000L, song.dateAdded);
    }

    /**
     * Verifies album art URI generation format contract.
     */
    @Test
    public void testGetAlbumArtUriString() {
        Song song = new Song(
                "track-42",
                "Get Lucky",
                "Daft Punk",
                "/storage/emulated/0/Music/get_lucky.mp3",
                "alb-99",
                "Random Access Memories",
                3,
                1690000000L
        );
        assertEquals("content://media/external/audio/albumart/alb-99", song.getAlbumArtUriString());
    }
}
