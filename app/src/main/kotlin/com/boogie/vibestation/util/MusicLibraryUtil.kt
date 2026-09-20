package com.boogie.vibestation.util

import android.content.ContentResolver
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import java.util.Locale

/**
 * Handles MediaStore querying, song extraction, and library search and sort operations.
 */
object MusicLibraryUtil {

    /** MediaStore columns read for each song; [buildSongFromCursor] maps rows by these column names. */
    val SONG_PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK
    )

    /**
     * Queries MediaStore audio collection, constructs Song instances, and groups them into albums.
     *
     * @param contentResolver ContentResolver to query MediaStore.
     * @param fireAlbums Set of album IDs marked as fire.
     * @param albumMap Target map to populate with discovered Album models.
     * @return List of parsed Song records.
     */
    fun queryMediaStoreSongs(
        contentResolver: ContentResolver,
        fireAlbums: Set<String>?,
        albumMap: MutableMap<String, Album>?
    ): ArrayList<Song> {
        val songs = ArrayList<Song>()
        try {
            // Android 10+ exposes the library-relative path; older releases only expose the absolute one
            val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("%Music/%")
            } else {
                "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%/Music/%")
            }

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, SONG_PROJECTION, selection, selectionArgs, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                do {
                    val song = buildSongFromCursor(cursor)
                    songs.add(song)
                    albumMap?.getOrPut(song.albumId) {
                        Album(song.albumId, song.album, song.artist, song.dateAdded).apply {
                            isFire = fireAlbums != null && song.albumId in fireAlbums
                        }
                    }?.songs?.add(song)
                } while (cursor.moveToNext())
            }
        } catch (ignored: Exception) {
        }
        return songs
    }

    /**
     * Extracts column data from an active MediaStore cursor row and instantiates a Song model.
     *
     * @param cursor Positioned MediaStore query cursor.
     * @return Constructed Song instance.
     */
    fun buildSongFromCursor(cursor: Cursor): Song {
        fun column(name: String) = cursor.getColumnIndexOrThrow(name)

        val id = cursor.getString(column(MediaStore.Audio.Media._ID)) ?: ""
        var title = cursor.getString(column(MediaStore.Audio.Media.TITLE))
        var artist = cursor.getString(column(MediaStore.Audio.Media.ARTIST))
        val path: String? = cursor.getString(column(MediaStore.Audio.Media.DATA))
        val albumId = cursor.getString(column(MediaStore.Audio.Media.ALBUM_ID)) ?: ""
        var albumName = cursor.getString(column(MediaStore.Audio.Media.ALBUM))
        val dateAdded = cursor.getLong(column(MediaStore.Audio.Media.DATE_ADDED))
        val trackNumber = cursor.getInt(column(MediaStore.Audio.Media.TRACK))

        if (title.isNullOrBlank()) title = "Unknown Title"
        if (artist.isNullOrBlank()) artist = "Unknown Artist"
        if (albumName.isNullOrBlank()) albumName = "Unknown Album"

        // Untagged files: fall back to the Artist/Album/track.ext folder convention
        if (artist.lowercase(Locale.getDefault()).contains("unknown") && path != null) {
            val segments = path.split("/")
            if (segments.size >= 3) {
                albumName = segments[segments.size - 2]
                artist = segments[segments.size - 3]
            }
        }

        return Song(id, title, artist, path, albumId, albumName, trackNumber, dateAdded)
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
    fun filterData(
        query: String?,
        allSongs: List<Song>,
        allAlbums: List<Album>,
        allPlaylists: List<Playlist>,
        displaySongs: MutableList<Song>,
        displayAlbums: MutableList<Album>,
        displayPlaylists: MutableList<Playlist>
    ) {
        val trimmedQuery = (query ?: "").lowercase(Locale.getDefault()).trim()
        displaySongs.clear()
        displayAlbums.clear()
        displayPlaylists.clear()

        if (trimmedQuery.isEmpty()) {
            displaySongs.addAll(allSongs)
            displayAlbums.addAll(allAlbums)
            displayPlaylists.addAll(allPlaylists)
        } else {
            fun String.matches() = lowercase(Locale.getDefault()).contains(trimmedQuery)
            allSongs.filterTo(displaySongs) { it.title.matches() || it.artist.matches() }
            allAlbums.filterTo(displayAlbums) { it.name.matches() || it.artist.matches() }
            allPlaylists.filterTo(displayPlaylists) { it.name.matches() }
        }

        // Stable sorts keep prior ordering within the favorite and non-favorite groups
        displayAlbums.sortWith { a, b -> b.isFire.compareTo(a.isFire) }
        displayPlaylists.sortWith { a, b -> b.isFire.compareTo(a.isFire) }
    }

    /**
     * Sorts song and album datasets alphabetically or by add date.
     *
     * @param sortType Sort selector key index (0: A-Z, 1: Z-A, 2: Newest).
     * @param songs Song list to sort.
     * @param albums Album list to sort.
     */
    fun sortData(sortType: Int, songs: MutableList<Song>, albums: MutableList<Album>) {
        when (sortType) {
            0 -> {
                songs.sortWith { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
                albums.sortWith { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
            }
            1 -> {
                songs.sortWith { a, b -> b.title.compareTo(a.title, ignoreCase = true) }
                albums.sortWith { a, b -> b.name.compareTo(a.name, ignoreCase = true) }
            }
            else -> {
                songs.sortWith { a, b -> b.dateAdded.compareTo(a.dateAdded) }
                albums.sortWith { a, b -> b.dateAdded.compareTo(a.dateAdded) }
            }
        }
    }
}
