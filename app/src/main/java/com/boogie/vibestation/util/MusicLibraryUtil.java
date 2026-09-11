package com.boogie.vibestation.util;

import android.content.ContentResolver;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import com.boogie.vibestation.models.Album;
import com.boogie.vibestation.models.Playlist;
import com.boogie.vibestation.models.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Handles MediaStore querying, song extraction, and library search and sort operations.
 */
public final class MusicLibraryUtil {

    private MusicLibraryUtil() {
        // Prevent instantiation
    }

    /**
     * Queries MediaStore audio collection, constructs Song instances, and groups them into albums.
     *
     * @param contentResolver ContentResolver to query MediaStore.
     * @param fireAlbums Set of album IDs marked as fire.
     * @param albumMap Target map to populate with discovered Album models.
     * @return List of parsed Song records.
     */
    public static ArrayList<Song> queryMediaStoreSongs(ContentResolver contentResolver, Set<String> fireAlbums, Map<String, Album> albumMap) {
        ArrayList<Song> tempSongs = new ArrayList<>();
        if (contentResolver == null) return tempSongs;
        try {
            Cursor rawCursor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rawCursor = contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        null,
                        MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
                        new String[]{"%Music/%"},
                        null
                );
            } else {
                rawCursor = contentResolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        null,
                        MediaStore.Audio.Media.DATA + " LIKE ?",
                        new String[]{"%/Music/%"},
                        null
                );
            }

            if (rawCursor != null) {
                try (Cursor musicCursor = rawCursor) {
                    if (musicCursor.moveToFirst()) {
                        do {
                            Song song = buildSongFromCursor(musicCursor);
                            tempSongs.add(song);

                            if (albumMap != null) {
                                if (!albumMap.containsKey(song.albumId)) {
                                    Album newAlbum = new Album(song.albumId, song.album, song.artist, song.dateAdded);
                                    newAlbum.isFire = fireAlbums != null && fireAlbums.contains(song.albumId);
                                    albumMap.put(song.albumId, newAlbum);
                                }
                                albumMap.get(song.albumId).songs.add(song);
                            }
                        } while (musicCursor.moveToNext());
                    }
                }
            }
        } catch (Exception ignored) {}
        return tempSongs;
    }

    /**
     * Extracts column data from an active MediaStore cursor row and instantiates a Song model.
     *
     * @param musicCursor Positioned MediaStore query cursor.
     * @return Constructed Song instance.
     */
    public static Song buildSongFromCursor(Cursor musicCursor) {
        String id = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
        String title = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
        String artist = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
        String path = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));
        String albumId = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID));
        String albumName = musicCursor.getString(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
        long dateAdded = musicCursor.getLong(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED));
        int trackNumber = musicCursor.getInt(musicCursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK));

        if (title == null || title.trim().isEmpty()) {
            title = "Unknown Title";
        }
        if (artist == null || artist.trim().isEmpty()) {
            artist = "Unknown Artist";
        }
        if (albumName == null || albumName.trim().isEmpty()) {
            albumName = "Unknown Album";
        }

        if (artist.toLowerCase(Locale.getDefault()).contains("unknown") && path != null) {
            try {
                String[] pathSegments = path.split("/");
                if (pathSegments.length >= 3) {
                    albumName = pathSegments[pathSegments.length - 2];
                    artist = pathSegments[pathSegments.length - 3];
                }
            } catch (Exception ignored) {}
        }

        return new Song(id, title, artist, path, albumId, albumName, trackNumber, dateAdded);
    }

    /**
     * Filters songs, albums, and playlists matching the search query and sorts favorites first.
     *
     * @param query Search query string input.
     * @param allSongs Master song collection.
     * @param allAlbums Master album collection.
     * @param allPlaylists Master playlist collection.
     * @param displaySongs Target list for filtered songs.
     * @param displayAlbums Target list for filtered albums.
     * @param displayPlaylists Target list for filtered playlists.
     */
    public static void filterData(String query, List<Song> allSongs, List<Album> allAlbums, List<Playlist> allPlaylists,
                                  List<Song> displaySongs, List<Album> displayAlbums, List<Playlist> displayPlaylists) {
        String trimmedQuery = query.toLowerCase(Locale.getDefault()).trim();
        displaySongs.clear();
        displayAlbums.clear();
        displayPlaylists.clear();

        if (trimmedQuery.isEmpty()) {
            displaySongs.addAll(allSongs);
            displayAlbums.addAll(allAlbums);
            displayPlaylists.addAll(allPlaylists);
        } else {
            for (Song song : allSongs) {
                if (song.title.toLowerCase(Locale.getDefault()).contains(trimmedQuery) || song.artist.toLowerCase(Locale.getDefault()).contains(trimmedQuery)) {
                    displaySongs.add(song);
                }
            }
            for (Album album : allAlbums) {
                if (album.name.toLowerCase(Locale.getDefault()).contains(trimmedQuery) || album.artist.toLowerCase(Locale.getDefault()).contains(trimmedQuery)) {
                    displayAlbums.add(album);
                }
            }
            for (Playlist playlist : allPlaylists) {
                if (playlist.name.toLowerCase(Locale.getDefault()).contains(trimmedQuery)) {
                    displayPlaylists.add(playlist);
                }
            }
        }

        Collections.sort(displayAlbums, (a, b) -> Boolean.compare(b.isFire, a.isFire));
        Collections.sort(displayPlaylists, (a, b) -> Boolean.compare(b.isFire, a.isFire));
    }

    /**
     * Sorts song and album datasets alphabetically or by add date.
     *
     * @param sortType Sort selector key index (0: A-Z, 1: Z-A, 2: Newest).
     * @param songs Song list to sort.
     * @param albums Album list to sort.
     */
    public static void sortData(int sortType, List<Song> songs, List<Album> albums) {
        Comparator<Song> songComparator = sortType == 0
                ? (a, b) -> a.title.compareToIgnoreCase(b.title)
                : sortType == 1 ? (a, b) -> b.title.compareToIgnoreCase(a.title)
                : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);

        Comparator<Album> albumComparator = sortType == 0
                ? (a, b) -> a.name.compareToIgnoreCase(b.name)
                : sortType == 1 ? (a, b) -> b.name.compareToIgnoreCase(a.name)
                : (a, b) -> Long.compare(b.dateAdded, a.dateAdded);

        Collections.sort(songs, songComparator);
        Collections.sort(albums, albumComparator);
    }
}
