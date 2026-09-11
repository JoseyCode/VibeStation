package com.boogie.vibestation.models;

import java.util.ArrayList;

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
}
