package com.example.retroclone;

import android.net.Uri;
import java.util.ArrayList;

public class Models {
    public static class Song {
        public final String id;
        public final String title;
        public final String artist;
        public final String path;
        public final String albumId;
        public final String album;
        public final int trackNumber;
        public final long dateAdded;

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

        public Uri getAlbumArtUri() {
            return Uri.parse("content://media/external/audio/albumart/" + albumId);
        }
    }

    public static class Album {
        public final String albumId;
        public final String name;
        public final String artist;
        public final long dateAdded;
        public final ArrayList<Song> songs = new ArrayList<>();

        public Album(String albumId, String name, String artist, long dateAdded) {
            this.albumId = albumId;
            this.name = name;
            this.artist = artist;
            this.dateAdded = dateAdded;
        }
    }

    public static class Playlist {
        public String name;
        public String imageUri;
        public final ArrayList<Song> songs = new ArrayList<>();

        public Playlist(String name, String imageUri) {
            this.name = name;
            this.imageUri = imageUri;
        }
    }
}