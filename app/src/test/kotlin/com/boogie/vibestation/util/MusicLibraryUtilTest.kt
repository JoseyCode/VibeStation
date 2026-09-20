package com.boogie.vibestation.util

import android.content.ContentResolver
import android.database.Cursor
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for MusicLibraryUtil row mapping, MediaStore grouping, search filtering, and sorting.
 * Cursors are an in-memory fake over MockK; no Android framework is needed.
 */
class MusicLibraryUtilTest {

    private class Row(
        val id: String? = "1",
        val title: String? = "Song",
        val artist: String? = "Artist",
        val path: String? = "/storage/emulated/0/Music/song.mp3",
        val albumId: String? = "a1",
        val album: String? = "Album",
        val dateAdded: Long = 100L,
        val track: Int = 1
    )

    /** Builds a Cursor over [rows] whose column names and lookups follow the real projection. */
    private fun cursorOf(vararg rows: Row): Cursor {
        val columns = MusicLibraryUtil.SONG_PROJECTION.toList()
        var position = -1
        val cursor = mockk<Cursor>()
        every { cursor.getColumnIndexOrThrow(any()) } answers { columns.indexOf(firstArg<String>()) }
        every { cursor.getString(any()) } answers {
            val row = rows[position]
            when (columns[firstArg<Int>()]) {
                "_id" -> row.id
                "title" -> row.title
                "artist" -> row.artist
                "_data" -> row.path
                "album_id" -> row.albumId
                "album" -> row.album
                else -> error("unexpected string column")
            }
        }
        every { cursor.getLong(any()) } answers { rows[position].dateAdded }
        every { cursor.getInt(any()) } answers { rows[position].track }
        every { cursor.moveToFirst() } answers {
            position = 0
            rows.isNotEmpty()
        }
        every { cursor.moveToNext() } answers { ++position < rows.size }
        every { cursor.close() } just runs
        return cursor
    }

    private fun resolverReturning(cursor: Cursor?): ContentResolver {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        return resolver
    }

    private fun song(title: String, artist: String = "Artist", dateAdded: Long = 0L) =
        Song(title, title, artist, "/m/$title.mp3", "alb", "Album", 1, dateAdded)

    private fun album(name: String, artist: String = "Artist", dateAdded: Long = 0L, fire: Boolean = false) =
        Album(name, name, artist, dateAdded).apply { isFire = fire }

    // buildSongFromCursor

    /**
     * Verifies every column of a fully tagged row maps onto the matching Song field.
     */
    @Test
    fun buildSongMapsAllColumns() {
        val cursor = cursorOf(Row("42", "Get Lucky", "Daft Punk", "/m/get_lucky.mp3", "alb-9", "RAM", 1690000000L, 3))
        cursor.moveToFirst()

        val song = MusicLibraryUtil.buildSongFromCursor(cursor)

        assertEquals(Song("42", "Get Lucky", "Daft Punk", "/m/get_lucky.mp3", "alb-9", "RAM", 3, 1690000000L), song)
        assertEquals("Get Lucky", song.title)
        assertEquals("Daft Punk", song.artist)
        assertEquals("RAM", song.album)
        assertEquals("alb-9", song.albumId)
        assertEquals(3, song.trackNumber)
        assertEquals(1690000000L, song.dateAdded)
    }

    /**
     * Verifies missing or blank tags get display placeholders and null IDs become empty strings.
     */
    @Test
    fun buildSongAppliesPlaceholdersForMissingTags() {
        val cursor = cursorOf(Row(id = null, title = "  ", artist = null, path = null, albumId = null, album = ""))
        cursor.moveToFirst()

        val song = MusicLibraryUtil.buildSongFromCursor(cursor)

        assertEquals("", song.id)
        assertEquals("Unknown Title", song.title)
        assertEquals("Unknown Artist", song.artist)
        assertEquals("Unknown Album", song.album)
        assertEquals("", song.albumId)
    }

    /**
     * Verifies untagged files recover artist and album from the Artist/Album/file folder convention.
     */
    @Test
    fun buildSongInfersArtistAndAlbumFromPathWhenUntagged() {
        val path = "/storage/emulated/0/Music/Daft Punk/Discovery/one_more_time.mp3"
        val cursor = cursorOf(Row(artist = null, album = null, path = path))
        cursor.moveToFirst()

        val song = MusicLibraryUtil.buildSongFromCursor(cursor)

        assertEquals("Daft Punk", song.artist)
        assertEquals("Discovery", song.album)
    }

    /**
     * Verifies tagged artists are kept and short or missing paths cannot drive the folder fallback.
     */
    @Test
    fun buildSongKeepsTagsWhenPathFallbackDoesNotApply() {
        val tagged = cursorOf(Row(artist = "Daft Punk", album = "Discovery", path = "/Music/Other/Folder/x.mp3"))
        tagged.moveToFirst()
        assertEquals("Daft Punk", MusicLibraryUtil.buildSongFromCursor(tagged).artist)

        val shortPath = cursorOf(Row(artist = null, album = null, path = "b/x.mp3"))
        shortPath.moveToFirst()
        assertEquals("Unknown Artist", MusicLibraryUtil.buildSongFromCursor(shortPath).artist)

        val noPath = cursorOf(Row(artist = null, album = null, path = null))
        noPath.moveToFirst()
        assertEquals("Unknown Album", MusicLibraryUtil.buildSongFromCursor(noPath).album)
    }

    /**
     * Characterizes a known quirk: the fallback fires for any artist containing "unknown", so a real
     * tagged artist such as "Unknown Mortal Orchestra" is replaced by folder names.
     */
    @Test
    fun buildSongOverwritesRealArtistContainingUnknown() {
        val path = "/storage/emulated/0/Music/Downloads/Misc/track.mp3"
        val cursor = cursorOf(Row(artist = "Unknown Mortal Orchestra", album = "II", path = path))
        cursor.moveToFirst()

        val song = MusicLibraryUtil.buildSongFromCursor(cursor)

        assertEquals("Downloads", song.artist)
        assertEquals("Misc", song.album)
    }

    // queryMediaStoreSongs

    /**
     * Verifies rows become songs, and songs are grouped into albums with the fire flag applied.
     */
    @Test
    fun queryGroupsSongsIntoAlbumsAndMarksFire() {
        val cursor = cursorOf(
            Row(id = "1", title = "A", albumId = "x", album = "Ex", artist = "P", dateAdded = 5L),
            Row(id = "2", title = "B", albumId = "x", album = "Ex", artist = "P", dateAdded = 6L),
            Row(id = "3", title = "C", albumId = "y", album = "Why", artist = "Q", dateAdded = 7L)
        )
        val albums = mutableMapOf<String, Album>()

        val songs = MusicLibraryUtil.queryMediaStoreSongs(resolverReturning(cursor), setOf("y"), albums)

        assertEquals(listOf("1", "2", "3"), songs.map { it.id })
        assertEquals(setOf("x", "y"), albums.keys)
        assertEquals(listOf("1", "2"), albums.getValue("x").songs.map { it.id })
        assertEquals("Ex", albums.getValue("x").name)
        assertEquals("P", albums.getValue("x").artist)
        assertEquals(5L, albums.getValue("x").dateAdded)
        assertFalse(albums.getValue("x").isFire)
        assertTrue(albums.getValue("y").isFire)
    }

    /**
     * Verifies null fire and album arguments are accepted: songs still return, and nothing is grouped.
     */
    @Test
    fun queryToleratesNullFireSetAndAlbumMap() {
        val songs = MusicLibraryUtil.queryMediaStoreSongs(resolverReturning(cursorOf(Row(), Row(id = "2"))), null, null)

        assertEquals(2, songs.size)
    }

    /**
     * Verifies an empty result, a null cursor, and a throwing query all produce an empty list.
     */
    @Test
    fun queryReturnsEmptyWhenNothingOrErrors() {
        assertTrue(MusicLibraryUtil.queryMediaStoreSongs(resolverReturning(cursorOf()), null, null).isEmpty())
        assertTrue(MusicLibraryUtil.queryMediaStoreSongs(resolverReturning(null), null, null).isEmpty())

        val failing = mockk<ContentResolver>()
        every { failing.query(any(), any(), any(), any(), any()) } throws SecurityException("no permission")
        assertTrue(MusicLibraryUtil.queryMediaStoreSongs(failing, null, null).isEmpty())
    }

    // filterData

    private class Display {
        val songs = mutableListOf<Song>()
        val albums = mutableListOf<Album>()
        val playlists = mutableListOf<Playlist>()
    }

    private fun filter(query: String?, songs: List<Song>, albums: List<Album>, playlists: List<Playlist>, into: Display = Display()) =
        into.also { MusicLibraryUtil.filterData(query, songs, albums, playlists, it.songs, it.albums, it.playlists) }

    /**
     * Verifies null, empty, and blank queries show the entire library.
     */
    @Test
    fun filterWithoutQueryReturnsEverything() {
        val songs = listOf(song("One"), song("Two"))
        val albums = listOf(album("Alpha"))
        val playlists = listOf(Playlist("Mix", null))

        for (query in listOf(null, "", "   ")) {
            val result = filter(query, songs, albums, playlists)
            assertEquals(songs, result.songs)
            assertEquals(albums, result.albums)
            assertEquals(playlists, result.playlists)
        }
    }

    /**
     * Verifies matching is case-insensitive, trimmed, and covers song title/artist, album name/artist,
     * and playlist name only.
     */
    @Test
    fun filterMatchesFieldsCaseInsensitivelyAndTrimmed() {
        val songs = listOf(song("Get Lucky", "Daft Punk"), song("Nightcall", "Kavinsky"))
        val albums = listOf(album("Discovery", "Daft Punk"), album("OutRun", "Kavinsky"))
        val playlists = listOf(Playlist("Daft Drive", null), Playlist("Gym", null))

        val result = filter("  DAFT ", songs, albums, playlists)

        assertEquals(listOf("Get Lucky"), result.songs.map { it.title })
        assertEquals(listOf("Discovery"), result.albums.map { it.name })
        assertEquals(listOf("Daft Drive"), result.playlists.map { it.name })
    }

    /**
     * Verifies a playlist is matched by name only, never by its songs, and no match yields empty lists.
     */
    @Test
    fun filterNoMatchYieldsEmptyLists() {
        val playlist = Playlist("Gym", null).apply { songs.add(song("Zebra")) }

        val result = filter("zebra", emptyList(), emptyList(), listOf(playlist))

        assertTrue(result.songs.isEmpty() && result.albums.isEmpty() && result.playlists.isEmpty())
    }

    /**
     * Verifies stale contents of the display lists are replaced, not appended to.
     */
    @Test
    fun filterReplacesPreviousDisplayContents() {
        val display = Display().apply { songs.add(song("Stale")) }

        filter("fresh", listOf(song("Fresh One")), emptyList(), emptyList(), display)

        assertEquals(listOf("Fresh One"), display.songs.map { it.title })
    }

    /**
     * Verifies favorite albums and playlists float to the top while keeping prior relative order.
     */
    @Test
    fun filterSortsFavoritesFirstStably() {
        val albums = listOf(album("A"), album("B", fire = true), album("C"), album("D", fire = true))
        val playlists = listOf(Playlist("P1", null), Playlist("P2", null).apply { isFire = true }, Playlist("P3", null))

        val result = filter(null, emptyList(), albums, playlists)

        assertEquals(listOf("B", "D", "A", "C"), result.albums.map { it.name })
        assertEquals(listOf("P2", "P1", "P3"), result.playlists.map { it.name })
    }

    // sortData

    private fun sorted(sortType: Int): Pair<List<String>, List<String>> {
        val songs = mutableListOf(song("banana", dateAdded = 2), song("Apple", dateAdded = 1), song("cherry", dateAdded = 3))
        val albums = mutableListOf(album("beta", dateAdded = 2), album("Alpha", dateAdded = 1), album("gamma", dateAdded = 3))
        MusicLibraryUtil.sortData(sortType, songs, albums)
        return songs.map { it.title } to albums.map { it.name }
    }

    /**
     * Verifies sort type 0 orders A-Z ignoring case.
     */
    @Test
    fun sortAlphabeticalIgnoresCase() {
        val (songs, albums) = sorted(0)

        assertEquals(listOf("Apple", "banana", "cherry"), songs)
        assertEquals(listOf("Alpha", "beta", "gamma"), albums)
    }

    /**
     * Verifies sort type 1 orders Z-A ignoring case.
     */
    @Test
    fun sortReverseAlphabeticalIgnoresCase() {
        val (songs, albums) = sorted(1)

        assertEquals(listOf("cherry", "banana", "Apple"), songs)
        assertEquals(listOf("gamma", "beta", "Alpha"), albums)
    }

    /**
     * Verifies sort type 2, and any unrecognized type, orders newest added first.
     */
    @Test
    fun sortNewestFirstIsTheDefault() {
        for (type in listOf(2, 99, -1)) {
            val (songs, albums) = sorted(type)
            assertEquals(listOf("cherry", "banana", "Apple"), songs)
            assertEquals(listOf("gamma", "beta", "Alpha"), albums)
        }
    }
}
