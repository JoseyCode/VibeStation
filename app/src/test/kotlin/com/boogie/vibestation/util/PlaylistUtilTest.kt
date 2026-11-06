package com.boogie.vibestation.util

import android.content.SharedPreferences
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PlaylistUtil persistence format, song re-association rules, and backup sanitizing.
 * SharedPreferences is mocked; org.json is the real library via the test classpath.
 */
class PlaylistUtilTest {

    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val saved = slot<String>()

    private val getLucky = song("id-1", "Get Lucky", "Daft Punk")
    private val nightcall = song("id-2", "Nightcall", "Kavinsky")

    private fun song(id: String, title: String, artist: String) =
        Song(id, title, artist, "/music/$id.mp3", "alb-$id", "Album", 1, 100L)

    /** Wires prefs so savePlaylists output is captured and the given JSON is served back on reads. */
    private fun stubPrefs(stored: String? = "[]") {
        every { prefs.edit() } returns editor
        every { editor.putString("playlists", capture(saved)) } returns editor
        every { editor.apply() } just runs
        every { prefs.getString("playlists", "[]") } returns stored
    }

    private fun parse(json: String, byId: Map<String, Song> = emptyMap(), byName: Map<String, Song> = emptyMap()): List<Playlist> {
        stubPrefs(json)
        return PlaylistUtil.parsePlaylists(prefs, byId, byName)
    }

    /**
     * Verifies the persisted JSON keys, since they are the on-disk contract for existing installs.
     */
    @Test
    fun savePlaylistsWritesExpectedJsonSchema() {
        stubPrefs()
        val playlist = Playlist("Gym", "content://cover/1").apply {
            description = "Workout"
            isFire = true
            cover = "c".repeat(32) + ".jpg"
            lastModified = 1234
            renamedFrom = "Old Gym"
            songs.add(getLucky)
        }

        PlaylistUtil.savePlaylists(prefs, listOf(playlist), nowMs = 555)

        val entry = JSONArray(saved.captured).getJSONObject(0)
        assertEquals("Gym", entry.getString("name"))
        assertEquals("content://cover/1", entry.getString("imageUri"))
        assertEquals("c".repeat(32) + ".jpg", entry.getString("cover"))
        assertEquals("Workout", entry.getString("description"))
        assertTrue(entry.getBoolean("isFire"))
        assertEquals(1234, entry.getLong("lastModified"))
        assertEquals("Old Gym", entry.getString("renamedFrom"))
        val songEntry = entry.getJSONArray("songData").getJSONObject(0)
        assertEquals("id-1", songEntry.getString("id"))
        assertEquals("Get Lucky", songEntry.getString("t"))
        assertEquals("Daft Punk", songEntry.getString("a"))
        assertEquals(555, songEntry.getLong("addedAt"))
        verify { editor.apply() }
    }

    /**
     * Verifies a null cover is stored as an empty string and read back as null.
     */
    @Test
    fun nullImageUriRoundTripsAsNull() {
        stubPrefs()
        PlaylistUtil.savePlaylists(prefs, listOf(Playlist("Plain", null)))

        assertEquals("", JSONArray(saved.captured).getJSONObject(0).getString("imageUri"))
        val restored = parse(saved.captured)
        assertNull(restored.single().imageUri)
    }

    /**
     * Verifies name, cover, description, fire flag, and track order all survive save then parse.
     */
    @Test
    fun saveThenParseRoundTrip() {
        stubPrefs()
        val original = Playlist("Road Trip", "content://cover/2").apply {
            description = "Long drive"
            isFire = true
            songs.addAll(listOf(nightcall, getLucky))
        }
        PlaylistUtil.savePlaylists(prefs, listOf(original))

        val restored = parse(saved.captured, byId = listOf(getLucky, nightcall).associateBy { it.id }).single()

        assertEquals("Road Trip", restored.name)
        assertEquals("content://cover/2", restored.imageUri)
        assertEquals("Long drive", restored.description)
        assertTrue(restored.isFire)
        assertEquals(listOf("id-2", "id-1"), restored.songs.map { it.id })
    }

    /**
     * Verifies an ID match wins over a name-key match when both maps resolve to different songs.
     */
    @Test
    fun parsePrefersIdMatchOverNameMatch() {
        val json = playlistJson("Mix", listOf(Triple("id-1", "Get Lucky", "Daft Punk")))

        val restored = parse(
            json,
            byId = mapOf("id-1" to getLucky),
            byName = mapOf("get lucky_daft punk" to nightcall)
        ).single()

        assertEquals(listOf(getLucky), restored.songs)
    }

    /**
     * Verifies a track whose ID changed (rescan, reinstall, or a different device's id) is re-found by
     * its normalized title_artist key.
     */
    @Test
    fun parseFallsBackToNormalizedNameKey() {
        val json = playlistJson("Mix", listOf(Triple("stale-id", "Get Lucky!", "daft punk")))

        val restored = parse(json, byName = mapOf("getlucky_daftpunk" to getLucky)).single()

        assertEquals(listOf(getLucky), restored.songs)
    }

    /**
     * Verifies tracks that match neither map are dropped while the playlist itself is kept.
     */
    @Test
    fun parseDropsUnmatchedSongsButKeepsPlaylist() {
        val json = playlistJson("Ghosts", listOf(Triple("gone", "Deleted Song", "Nobody")))

        val restored = parse(json)

        assertEquals("Ghosts", restored.single().name)
        assertTrue(restored.single().songs.isEmpty())
    }

    /**
     * Verifies missing optional fields fall back to defaults instead of failing the parse.
     */
    @Test
    fun parseAppliesDefaultsForOptionalFields() {
        val json = """[{"name":"Bare","songData":[]}]"""

        val restored = parse(json).single()

        assertNull(restored.imageUri)
        assertEquals("", restored.description)
        assertFalse(restored.isFire)
    }

    /**
     * Verifies unreadable or empty stored data yields an empty list rather than an exception.
     */
    @Test
    fun parseReturnsEmptyForMalformedOrEmptyStorage() {
        assertTrue(parse("not json").isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("""{"name":"object, not array"}""").isEmpty())
    }

    /**
     * Characterizes current behavior: one corrupt entry stops the parse, keeping only earlier playlists.
     */
    @Test
    fun parseKeepsPlaylistsBeforeCorruptEntry() {
        val good = playlistJson("Good", emptyList()).removeSurrounding("[", "]")
        val json = """[$good,{"name":"Broken"}]"""

        val restored = parse(json)

        assertEquals(listOf("Good"), restored.map { it.name })
    }

    /**
     * Verifies a failing preferences write is swallowed, matching the current fire-and-forget contract.
     */
    @Test
    fun savePlaylistsSwallowsPreferenceFailures() {
        every { prefs.edit() } throws IllegalStateException("prefs unavailable")

        PlaylistUtil.savePlaylists(prefs, listOf(Playlist("Any", null)))
    }

    /**
     * Verifies legacy inline b64 cover payloads are stripped from every entry and other fields survive.
     */
    @Test
    fun sanitizeBackupStripsB64Payloads() {
        val backup = """[{"name":"A","b64":"AAAA","imageUri":"x"},{"name":"B"},{"name":"C","b64":"BBBB"}]"""

        val cleaned = JSONArray(PlaylistUtil.sanitizeBackup(backup))

        assertEquals(3, cleaned.length())
        for (i in 0 until cleaned.length()) {
            assertFalse(cleaned.getJSONObject(i).has("b64"))
        }
        assertEquals("x", cleaned.getJSONObject(0).getString("imageUri"))
        assertEquals("B", cleaned.getJSONObject(1).getString("name"))
    }

    /**
     * Verifies a backup that is not a JSON array is rejected so a bad file cannot overwrite playlists.
     */
    @Test
    fun sanitizeBackupRejectsNonArrayContent() {
        assertFailsWith<JSONException> { PlaylistUtil.sanitizeBackup("""{"name":"object"}""") }
        assertFailsWith<JSONException> { PlaylistUtil.sanitizeBackup("garbage") }
    }

    /**
     * Verifies the name key is the shared normalized match key, so a track saved by one device is found
     * by another whatever its casing or punctuation, and is the key parsePlaylists falls back on.
     */
    @Test
    fun songNameKeyIsTheSharedMatchKey() {
        assertEquals("getlucky_daftpunk", PlaylistUtil.songNameKey("Get Lucky", "Daft Punk"))
        assertEquals("a_b", PlaylistUtil.songNameKey(" A !", "B "))
        assertEquals("unknown_track", PlaylistUtil.songNameKey("", ""))
    }

    /**
     * Verifies a track from before add times were recorded keeps 0 through save, so a deletion made
     * elsewhere still wins over it, while a genuinely new track is stamped.
     */
    @Test
    fun legacyTracksKeepZeroAddTimeAndNewTracksAreStamped() {
        val legacy = parse(playlistJson("P", listOf(Triple("id-1", "Get Lucky", "Daft Punk"))), byId = mapOf("id-1" to getLucky)).single()
        legacy.songs.add(nightcall)

        PlaylistUtil.savePlaylists(prefs, listOf(legacy), nowMs = 777)

        val songs = JSONArray(saved.captured).getJSONObject(0).getJSONArray("songData")
        assertEquals(0, songs.getJSONObject(0).getLong("addedAt"))
        assertEquals(777, songs.getJSONObject(1).getLong("addedAt"))
    }

    /**
     * Verifies a stored add time survives a round trip, and a track removed then added back is treated
     * as new, so it beats the removal that was reported to the server.
     */
    @Test
    fun addTimesRoundTripAndResetOnReAdd() {
        stubPrefs()
        val playlist = Playlist("P", null).apply { songs.addAll(listOf(getLucky, nightcall)) }
        PlaylistUtil.savePlaylists(prefs, listOf(playlist), nowMs = 100)
        val restored = parse(saved.captured, byId = listOf(getLucky, nightcall).associateBy { it.id }).single()

        restored.songs.remove(nightcall)
        PlaylistUtil.savePlaylists(prefs, listOf(restored), nowMs = 200)
        restored.songs.add(nightcall)
        PlaylistUtil.savePlaylists(prefs, listOf(restored), nowMs = 300)

        val songs = JSONArray(saved.captured).getJSONObject(0).getJSONArray("songData")
        assertEquals(100, songs.getJSONObject(0).getLong("addedAt"))
        assertEquals(300, songs.getJSONObject(1).getLong("addedAt"))
    }

    /**
     * Verifies the sync fields are read back, and legacy entries without them get their defaults.
     */
    @Test
    fun parseReadsSyncFieldsWithDefaults() {
        val fresh = JSONObject().put("name", "New").put("cover", "x.png").put("lastModified", 42)
            .put("renamedFrom", "Was").put("songData", JSONArray())
        val json = JSONArray().put(fresh).put(JSONObject().put("name", "Old").put("songData", JSONArray())).toString()

        val (parsedFresh, legacy) = parse(json)

        assertEquals("x.png", parsedFresh.cover)
        assertEquals(42, parsedFresh.lastModified)
        assertEquals("Was", parsedFresh.renamedFrom)
        assertEquals("", legacy.cover)
        assertEquals(0, legacy.lastModified)
        assertNull(legacy.renamedFrom)
    }

    /**
     * Verifies a saved entry whose ID is gone is re-linked through a map built with songNameKey,
     * so producer and consumer of the key cannot drift apart.
     */
    @Test
    fun parsePlaylistsRelinksByNameKeyMadeWithSongNameKey() {
        val byName = mapOf(PlaylistUtil.songNameKey(nightcall.title, nightcall.artist) to nightcall)

        val playlists = parse(playlistJson("P", listOf(Triple("stale-id", "NIGHTCALL", "kavinsky"))), byName = byName)

        assertEquals(listOf(nightcall), playlists.single().songs)
    }

    private fun named(name: String) = Playlist(name, null)

    /**
     * Verifies a drag in an unfiltered list reorders both lists identically.
     */
    @Test
    fun movePlaylistReordersBothListsWhenUnfiltered() {
        val (a, b, c) = listOf(named("A"), named("B"), named("C"))
        val all = mutableListOf(a, b, c)
        val display = mutableListOf(a, b, c)

        PlaylistUtil.movePlaylist(all, display, 0, 2)

        assertEquals(listOf(b, c, a), display)
        assertEquals(listOf(b, c, a), all)
    }

    /**
     * Verifies a drag in a filtered list lands at the dropped-on playlist's position in the master
     * list, leaving hidden playlists in place around it.
     */
    @Test
    fun movePlaylistMirrorsIntoMasterListWhenFiltered() {
        val a = named("A")
        val b = named("B")
        val c = named("C")
        val d = named("D")
        val all = mutableListOf(a, b, c, d)
        val display = mutableListOf(a, c, d)

        PlaylistUtil.movePlaylist(all, display, 2, 0)

        assertEquals(listOf(d, a, c), display)
        assertEquals(listOf(d, a, b, c), all)
    }

    /**
     * Verifies a playlist absent from the master list still moves in the displayed list, without
     * touching the master.
     */
    @Test
    fun movePlaylistOnlyMovesDisplayWhenMissingFromMaster() {
        val (a, b, ghost) = listOf(named("A"), named("B"), named("Ghost"))
        val all = mutableListOf(a, b)
        val display = mutableListOf(a, b, ghost)

        PlaylistUtil.movePlaylist(all, display, 2, 0)

        assertEquals(listOf(ghost, a, b), display)
        assertEquals(listOf(a, b), all)
    }

    private fun playlistJson(name: String, songs: List<Triple<String, String, String>>): String {
        val songArray = JSONArray()
        for ((id, title, artist) in songs) {
            songArray.put(JSONObject().put("id", id).put("t", title).put("a", artist))
        }
        return JSONArray().put(JSONObject().put("name", name).put("songData", songArray)).toString()
    }
}
