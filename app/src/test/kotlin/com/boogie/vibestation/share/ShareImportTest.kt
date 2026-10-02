package com.boogie.vibestation.share

import android.content.SharedPreferences
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.PlaylistUtil
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests for [ShareImport]: unique names, safe destinations, and playlist entries built from local ids. */
class ShareImportTest {

    private fun track(
        title: String = "Song",
        artist: String = "Artist",
        album: String = "Album",
        fileName: String = "Song.mp3",
        mime: String = "audio/mpeg"
    ) = ShareTrack(title, artist, album, 1000, 10, fileName, mime)

    private fun manifest(vararg tracks: ShareTrack, description: String = "") =
        ShareManifest(ShareKind.PLAYLIST, "Mix", description, "", tracks.toList())

    /** A free name is kept, a taken one gets the first free number, and a blank one gets a default. */
    @Test
    fun uniqueName() {
        assertEquals("Mix", ShareImport.uniqueName("Mix", listOf("Other")))
        assertEquals("Mix (2)", ShareImport.uniqueName("Mix", listOf("Mix")))
        assertEquals("Mix (3)", ShareImport.uniqueName(" Mix ", listOf("Mix", "Mix (2)")))
        assertEquals("Mix (2)", ShareImport.uniqueName("Mix", listOf("Mix", "Mix (3)")))
        assertEquals("Shared playlist", ShareImport.uniqueName("  ", emptyList()))
        assertEquals("Shared playlist (2)", ShareImport.uniqueName("", listOf("Shared playlist")))
    }

    /** Received files go to Music/Artist/Album/, with blank tags replaced by Unknown. */
    @Test
    fun relativePathLayout() {
        assertEquals("Music/Daft Punk/Discovery/", ShareImport.relativePath("Daft Punk", "Discovery"))
        assertEquals("Music/Unknown Artist/Unknown Album/", ShareImport.relativePath(" ", ""))
    }

    /** A hostile artist or album can never climb out of Music/ or add path segments. */
    @Test
    fun relativePathCannotEscapeMusic() {
        for (evil in listOf("..", ".", "../..", "a/../b", "..\\..", "/etc", "....//")) {
            val path = ShareImport.relativePath(evil, evil)
            assertTrue(path.startsWith("Music/") && path.endsWith("/"), path)
            val segments = path.removeSuffix("/").split("/")
            assertEquals(3, segments.size, path)
            assertTrue(segments.none { it == ".." || it == "." || it.isEmpty() }, path)
        }
    }

    /** Leading dots are dropped: the media scanner skips dot-prefixed folders, so the track would vanish. */
    @Test
    fun relativePathDropsLeadingDots() {
        assertEquals("Music/Hidden/Album/", ShareImport.relativePath(".Hidden", "..Album"))
    }

    /** Overlong segments are capped and never end with a dot or space. */
    @Test
    fun relativePathCapsLength() {
        val segment = ShareImport.relativePath("a".repeat(500), "b. ").split("/")
        assertEquals(100, segment[1].length)
        assertEquals("b", segment[2])
    }

    /** The sender's file name is kept when safe; unusable ones fall back to title plus a matching extension. */
    @Test
    fun displayName() {
        assertEquals("Song.flac", ShareImport.displayName(track(fileName = "Song.flac")))
        assertEquals("a_b.mp3", ShareImport.displayName(track(fileName = "a/b.mp3")))
        assertEquals("Hey.flac", ShareImport.displayName(track(title = "Hey", fileName = "..", mime = "audio/flac")))
        assertEquals("Hey.mp3", ShareImport.displayName(track(title = "Hey", fileName = "", mime = "weird/type")))
        assertEquals("track.mp3", ShareImport.displayName(track(title = "", fileName = "")))
    }

    /** newTrack carries tags, destination, and a MIME type MediaStore will accept. */
    @Test
    fun newTrack() {
        val created = ShareImport.newTrack(track("T", "Ar", "Al", "T.flac", "audio/flac"))
        assertEquals("T.flac", created.displayName)
        assertEquals("T", created.title)
        assertEquals("Ar", created.artist)
        assertEquals("Al", created.album)
        assertEquals("audio/flac", created.mimeType)
        assertEquals("Music/Ar/Al/", created.relativePath)
    }

    /** A non-audio MIME type from the peer is replaced, since MediaStore would refuse the insert. */
    @Test
    fun nonAudioMimeIsReplaced() {
        assertEquals("audio/flac", ShareImport.newTrack(track(fileName = "x.FLAC", mime = "video/mp4")).mimeType)
        assertEquals("audio/mpeg", ShareImport.newTrack(track(fileName = "x.unknown", mime = "")).mimeType)
    }

    /** The playlist entry uses the receiver's ids and skips tracks that could not be resolved. */
    @Test
    fun playlistEntryUsesLocalIds() {
        val m = manifest(track("A"), track("B"), track("C"), description = "Road trip")
        val resolved = listOf(LocalTrack("101", "A", "Artist", 1), null, LocalTrack("103", "C", "Artist", 1))

        val entry = ShareImport.playlistEntry(m, "Mix (2)", "file:///cover.jpg", resolved)

        assertEquals("Mix (2)", entry.getString("name"))
        assertEquals("file:///cover.jpg", entry.getString("imageUri"))
        assertEquals("Road trip", entry.getString("description"))
        assertFalse(entry.getBoolean("isFire"))
        val songs = entry.getJSONArray("songData")
        assertEquals(listOf("101", "103"), (0 until songs.length()).map { songs.getJSONObject(it).getString("id") })
        assertEquals("C", songs.getJSONObject(1).getString("t"))
    }

    /** No cover means an empty imageUri, which parsePlaylists reads back as null. */
    @Test
    fun playlistEntryWithoutCover() {
        val entry = ShareImport.playlistEntry(manifest(track()), "Mix", null, listOf(LocalTrack("1", "Song", "Artist", 1)))
        assertEquals("", entry.getString("imageUri"))
    }

    /** One resolved entry per manifest track is required; a mismatch is a programming error. */
    @Test
    fun playlistEntryRequiresOneEntryPerTrack() {
        assertFailsWith<IllegalArgumentException> {
            ShareImport.playlistEntry(manifest(track(), track()), "Mix", null, listOf(null))
        }
    }

    /** Names are read from stored JSON and entries are appended, keeping what was there. */
    @Test
    fun namesAndAppend() {
        val stored = JSONArray().put(JSONObject().put("name", "Old")).toString()
        assertEquals(listOf("Old"), ShareImport.playlistNames(stored))

        val updated = ShareImport.appendPlaylist(stored, JSONObject().put("name", "New"))

        assertEquals(listOf("Old", "New"), ShareImport.playlistNames(updated))
    }

    /** Corrupt stored JSON must raise instead of being overwritten by the share. */
    @Test
    fun appendRefusesCorruptStoredJson() {
        assertFailsWith<JSONException> { ShareImport.appendPlaylist("not json", JSONObject()) }
    }

    /**
     * The point of the ID rewrite: the sender's song with id "5" must land on the receiver's own copy
     * (id "12"), not on whatever unrelated song happens to have id "5" here.
     */
    @Test
    fun importedPlaylistResolvesToReceiversSongsEvenWhenIdsCollide() {
        fun song(id: String, title: String) = Song(id, title, "Artist", "/m/$id.mp3", "al", "Album", 1, 0)
        val unrelatedFive = song("5", "Unrelated")
        val wanted = song("12", "Wanted")
        val m = manifest(track("Wanted"))
        val entry = ShareImport.playlistEntry(m, "Mix", null, listOf(LocalTrack("12", "Wanted", "Artist", 1)))
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString("playlists", "[]") } returns ShareImport.appendPlaylist("[]", entry)

        val playlists = PlaylistUtil.parsePlaylists(prefs, mapOf("5" to unrelatedFive, "12" to wanted), emptyMap())

        assertEquals(listOf(wanted), playlists.single().songs)
    }
}
