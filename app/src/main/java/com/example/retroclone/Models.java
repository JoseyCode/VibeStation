package com.example.retroclone;

import android.net.Uri;
import java.util.ArrayList;

/**
 * Models container class holding the database structures representing songs, albums, and playlists.
 */
public class Models {

    /**
     * Represents a single audio track retrieved from the MediaStore database.
     */
    public static class Song {
        public final String id;
        public final String title;
        public final String artist;
        public final String path;
        public final String albumId;
        public final String album;
        public final int trackNumber;
        public final long dateAdded;

        /**
         * Constructor for building a Song object.
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

        /**
         * Parses and returns the MediaStore URI pointing to this song's album art.
         *
         * @return Content provider URI for the album artwork
         */
        public Uri getAlbumArtUri() {
            return Uri.parse("content://media/external/audio/albumart/" + albumId);
        }
    }

    /**
     * Represents a grouped collection of Songs under a unique album.
     */
    public static class Album {
        public final String albumId;
        public final String name;
        public final String artist;
        public final long dateAdded;
        public final ArrayList<Song> songs = new ArrayList<>();

        /**
         * Constructor for building an Album object.
         */
        public Album(String albumId, String name, String artist, long dateAdded) {
            this.albumId = albumId;
            this.name = name;
            this.artist = artist;
            this.dateAdded = dateAdded;
        }
    }

    /**
     * Represents a user-customizable playlist containing a list of Songs.
     */
    public static class Playlist {
        public String name;
        public String imageUri;
        public final ArrayList<Song> songs = new ArrayList<>();

        /**
         * Constructor for building a Playlist object.
         */
        public Playlist(String name, String imageUri) {
            this.name = name;
            this.imageUri = imageUri;
        }
    }
}