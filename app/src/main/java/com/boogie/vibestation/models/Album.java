package com.boogie.vibestation.models;

import java.util.ArrayList;
import java.util.Objects;

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

    /**
     * Checks equality based on unique album identifier.
     *
     * @param o Comparison object.
     * @return True if objects represent the same album record.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Album album = (Album) o;
        return Objects.equals(albumId, album.albumId);
    }

    /**
     * Computes hash code from unique album identifier.
     *
     * @return Integer hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(albumId);
    }
}
