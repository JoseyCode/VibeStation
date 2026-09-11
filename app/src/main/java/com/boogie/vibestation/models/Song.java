package com.boogie.vibestation.models;

import android.net.Uri;

import java.util.Objects;

/**
 * Represents a single audio track retrieved from the MediaStore database.
 */
public class Song {
    public final String id;
    public final String title;
    public final String artist;
    public final String path;
    public final String albumId;
    public final String album;
    public final int trackNumber;
    public final long dateAdded;

    /**
     * Constructs a Song instance with track metadata.
     */
    public Song(String id, String title, String artist, String path, String albumId, String album, int trackNumber, long dateAdded) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.path = path;
        this.albumId = albumId;
        this.album = album;
        this.trackNumber = trackNumber;
        this.dateAdded = dateAdded;
    }

    public static final String ALBUM_ART_URI_PREFIX = "content://media/external/audio/albumart/";

    /**
     * Builds the content URI string pointing to this song's album art.
     *
     * @return Content provider URI string for album artwork.
     */
    public String getAlbumArtUriString() {
        return ALBUM_ART_URI_PREFIX + albumId;
    }

    /**
     * Parses and returns the MediaStore URI pointing to this song's album art.
     *
     * @return Content provider URI for the album artwork
     */
    public Uri getAlbumArtUri() {
        return Uri.parse(getAlbumArtUriString());
    }

    /**
     * Checks equality based on unique track ID and storage path.
     *
     * @param o Comparison object.
     * @return True if objects represent the same physical track record.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Song song = (Song) o;
        return Objects.equals(id, song.id) && Objects.equals(path, song.path);
    }

    /**
     * Computes hash code from unique track ID and storage path.
     *
     * @return Integer hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, path);
    }
}
