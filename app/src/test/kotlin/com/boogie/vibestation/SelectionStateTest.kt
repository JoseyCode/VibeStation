package com.boogie.vibestation

import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SelectionState toggling and the playlist edits applied to a selection: the duplicate
 * counting, ID-based removal and summary text MainActivity used to do inline.
 */
class SelectionStateTest {

    private fun song(id: String, path: String? = "/m/$id.mp3") = Song(id, "Title $id", "Artist", path, "alb", "Album", 1, 0L)

    private fun selectionOf(vararg songs: Song) = SelectionState().apply { songs.forEach(::toggle) }

    private fun playlistOf(vararg songs: Song) = Playlist("P", null).apply { this.songs.addAll(songs) }

    private fun Playlist.ids() = songs.map { it.id }

    /**
     * Verifies a new state is inactive and empty.
     */
    @Test
    fun startsInactiveAndEmpty() {
        val selection = SelectionState()

        assertFalse(selection.isActive)
        assertTrue(selection.isEmpty)
        assertEquals(0, selection.size)
    }

    /**
     * Verifies the first toggle activates the mode and selects the song.
     */
    @Test
    fun toggleActivatesAndSelects() {
        val a = song("a")
        val selection = SelectionState()

        selection.toggle(a)

        assertTrue(selection.isActive)
        assertEquals(1, selection.size)
        assertTrue(a in selection)
    }

    /**
     * Verifies toggling a selected song deselects it, but emptying the selection keeps the mode
     * active until the caller clears it.
     */
    @Test
    fun toggleTwiceDeselectsButStaysActive() {
        val a = song("a")
        val selection = selectionOf(a)

        selection.toggle(a)

        assertTrue(selection.isEmpty)
        assertFalse(a in selection)
        assertTrue(selection.isActive)
    }

    /**
     * Verifies songs are matched by ID and path (Song equality), so a separate instance of the same
     * track deselects the original.
     */
    @Test
    fun toggleUsesSongEquality() {
        val selection = selectionOf(song("a"))

        selection.toggle(song("a"))
        assertTrue(selection.isEmpty)

        selection.toggle(song("a"))
        selection.toggle(song("a", path = "/other/a.mp3"))
        assertEquals(2, selection.size)
    }

    /**
     * Verifies clear empties the selection and leaves selection mode.
     */
    @Test
    fun clearEmptiesAndDeactivates() {
        val selection = selectionOf(song("a"), song("b"))

        selection.clear()

        assertTrue(selection.isEmpty)
        assertFalse(selection.isActive)
    }

    /**
     * Verifies addMissingTo appends new songs, skips songs whose ID is already present, and
     * reports both counts.
     */
    @Test
    fun addMissingToSkipsDuplicatesById() {
        val playlist = playlistOf(song("a"), song("b"))
        val selection = selectionOf(song("b"), song("c"), song("d"))

        val result = selection.addMissingTo(playlist)

        assertEquals(SelectionState.AddResult(added = 2, duplicates = 1), result)
        assertEquals(setOf("a", "b", "c", "d"), playlist.ids().toSet())
        assertEquals(4, playlist.songs.size)
    }

    /**
     * Verifies a song with the same ID but a different path counts as a duplicate, even though it
     * is a distinct Song for selection purposes.
     */
    @Test
    fun addMissingToTreatsSameIdDifferentPathAsDuplicate() {
        val playlist = playlistOf(song("a", path = "/old/a.mp3"))
        val selection = selectionOf(song("a", path = "/new/a.mp3"))

        val result = selection.addMissingTo(playlist)

        assertEquals(SelectionState.AddResult(added = 0, duplicates = 1), result)
        assertEquals(listOf("/old/a.mp3"), playlist.songs.map { it.path })
    }

    /**
     * Verifies addMissingTo with an empty selection changes nothing and reports zero.
     */
    @Test
    fun addMissingToWithEmptySelectionIsNoOp() {
        val playlist = playlistOf(song("a"))

        val result = SelectionState().addMissingTo(playlist)

        assertEquals(SelectionState.AddResult(0, 0), result)
        assertEquals(listOf("a"), playlist.ids())
    }

    /**
     * Verifies addAllTo appends every selected song with no duplicate check, so a track already in
     * the playlist is added again.
     */
    @Test
    fun addAllToAppendsWithoutDeduplicating() {
        val playlist = playlistOf(song("a"))
        val selection = selectionOf(song("a"), song("b"))

        selection.addAllTo(playlist)

        assertEquals(3, playlist.songs.size)
        assertEquals(setOf("a", "b"), playlist.ids().toSet())
    }

    /**
     * Verifies removeFrom drops playlist tracks by ID (including a different path) and keeps the
     * rest in order.
     */
    @Test
    fun removeFromRemovesByIdAndKeepsOrder() {
        val playlist = playlistOf(song("a"), song("b", path = "/old/b.mp3"), song("c"), song("d"))
        val selection = selectionOf(song("b", path = "/new/b.mp3"), song("d"))

        selection.removeFrom(playlist)

        assertEquals(listOf("a", "c"), playlist.ids())
    }

    /**
     * Verifies removeFrom leaves the selection itself untouched (the activity clears it separately).
     */
    @Test
    fun removeFromDoesNotClearSelection() {
        val selection = selectionOf(song("a"))

        selection.removeFrom(playlistOf(song("a")))

        assertEquals(1, selection.size)
        assertTrue(selection.isActive)
    }

    /**
     * Verifies the summary text, with and without the duplicate note.
     */
    @Test
    fun addResultMessageMentionsDuplicatesOnlyWhenPresent() {
        assertEquals("Added 3 songs to Road Trip", SelectionState.AddResult(3, 0).message("Road Trip"))
        assertEquals(
            "Added 2 songs to Road Trip (1 duplicates skipped)",
            SelectionState.AddResult(2, 1).message("Road Trip")
        )
    }
}
