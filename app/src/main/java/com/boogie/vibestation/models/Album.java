package com.boogie.vibestation.models;

import java.util.ArrayList;

/**
 * Represents a grouped collection of Songs under a unique album.
 */
public class Album {
    public final String albumId;
    public final String name;
    public final String artist;
    public final long dateAdded;
    public boolean isFire = false;
    public final ArrayList<Song> songs = new ArrayList<>();

    /**
     * Constructs an Album instance with metadata.
     */
    public Album(String albumId, String name, String artist, long dateAdded) {
        this.albumId = albumId;
        this.name = name;
        this.artist = artist;
        this.dateAdded = dateAdded;
    }
}
