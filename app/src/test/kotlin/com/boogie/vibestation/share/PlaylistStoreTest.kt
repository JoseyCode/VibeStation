package com.boogie.vibestation.share

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlaylistStoreTest {
    private val editor = mockk<SharedPreferences.Editor>()
    private val prefs = mockk<SharedPreferences>()
    private val written = slot<String>()

    init {
        every { prefs.edit() } returns editor
        every { editor.putString("playlists", capture(written)) } returns editor
        every { editor.commit() } returns true
    }

    private fun stored(json: String?) {
        every { prefs.getString("playlists", null) } returns json
    }

    @Test
    fun namesAreReadInStoredOrder() {
        stored("""[{"name":"A"},{"name":"B"}]""")
        assertEquals(listOf("A", "B"), PlaylistStore(prefs).names())
    }

    @Test
    fun noStoredPlaylistsMeansNoNames() {
        stored(null)
        assertTrue(PlaylistStore(prefs).names().isEmpty())
    }

    @Test
    fun unreadableStoredPlaylistsMeanNoNames() {
        stored("not json")
        assertTrue(PlaylistStore(prefs).names().isEmpty())
    }

    @Test
    fun addAppendsToExistingPlaylists() {
        stored("""[{"name":"A"}]""")
        PlaylistStore(prefs).add(JSONObject().put("name", "B"))
        val saved = JSONArray(written.captured)
        assertEquals(listOf("A", "B"), (0 until saved.length()).map { saved.getJSONObject(it).getString("name") })
    }

    @Test
    fun addCreatesTheListWhenNothingIsStored() {
        stored(null)
        PlaylistStore(prefs).add(JSONObject().put("name", "B"))
        assertEquals(1, JSONArray(written.captured).length())
    }

    @Test
    fun addNeverOverwritesUnreadablePlaylists() {
        stored("not json")
        assertFailsWith<IOException> { PlaylistStore(prefs).add(JSONObject().put("name", "B")) }
        verify(exactly = 0) { editor.putString(any(), any()) }
    }

    @Test
    fun addReportsAFailedWrite() {
        stored("[]")
        every { editor.commit() } returns false
        assertFailsWith<IOException> { PlaylistStore(prefs).add(JSONObject().put("name", "B")) }
    }
}
