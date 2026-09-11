package com.boogie.vibestation.models;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Represents a user-customizable playlist containing a list of Songs.
 */
public class Playlist {
    public String name;
    public String imageUri;
    public String description;
    public boolean isFire = false;
    public final ArrayList<Song> songs = new ArrayList<>();

    /**
     * Constructs a Playlist instance with initial name and optional cover image URI.
     */
    public Playlist(String name, String imageUri) {
        this.name = name;
        this.imageUri = imageUri;
        this.description = "";
    }

    /**
     * Checks equality based on unique playlist name.
     *
     * @param o Comparison object.
     * @return True if objects represent the same playlist by name.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Playlist playlist = (Playlist) o;
        return Objects.equals(name, playlist.name);
    }

    /**
     * Computes hash code from playlist name.
     *
     * @return Integer hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
