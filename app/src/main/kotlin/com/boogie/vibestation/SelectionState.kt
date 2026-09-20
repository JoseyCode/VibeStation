package com.boogie.vibestation

import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song

/**
 * Batch-selection state for the song lists, plus the playlist edits applied to a selection. Pure
 * state and list arithmetic, kept out of [MainActivity] so the counting and de-duplication rules can be
 * tested without views; the activity keeps only toasts, dialogs and visibility.
 */
internal class SelectionState {

    // A hash set on purpose: selection order is not preserved when the songs are handed to a playlist
    private val selected = HashSet<Song>()

    /** True from the first toggle until [clear]; drives the toolbar swap and checkbox visibility. */
    var isActive = false
        private set

    /** Number of selected songs. */
    val size: Int
        get() = selected.size

    /** True when no song is selected. */
    val isEmpty: Boolean
        get() = selected.isEmpty()

    /** Whether [song] is currently selected. */
    operator fun contains(song: Song): Boolean = song in selected

    /**
     * Enters selection mode if needed and flips [song] in or out of the selection. Emptying the
     * selection does not leave the mode; the caller decides whether to [clear].
     */
    fun toggle(song: Song) {
        isActive = true
        if (!selected.remove(song)) {
            selected.add(song)
        }
    }

    /** Empties the selection and leaves selection mode. */
    fun clear() {
        isActive = false
        selected.clear()
    }

    /**
     * Appends every selected song to [playlist] without checking for existing entries, used when
     * the playlist was just created for this selection.
     */
    fun addAllTo(playlist: Playlist) {
        playlist.songs.addAll(selected)
    }

    /**
     * Appends the selected songs that [playlist] does not already contain. A song counts as already
     * present when a track with the same ID exists, even if its path differs.
     *
     * @return How many songs were added and how many were skipped as duplicates.
     */
    fun addMissingTo(playlist: Playlist): AddResult {
        var added = 0
        var duplicates = 0
        for (song in selected) {
            if (playlist.songs.any { it.id == song.id }) {
                duplicates++
            } else {
                playlist.songs.add(song)
                added++
            }
        }
        return AddResult(added, duplicates)
    }

    /** Removes every track from [playlist] whose ID matches a selected song. */
    fun removeFrom(playlist: Playlist) {
        val selectedIds = selected.mapTo(HashSet()) { it.id }
        playlist.songs.removeAll { it.id in selectedIds }
    }

    /**
     * Outcome of [addMissingTo].
     *
     * @property added      Songs appended to the playlist.
     * @property duplicates Songs skipped because the playlist already had a track with that ID.
     */
    data class AddResult(val added: Int, val duplicates: Int) {

        /** User-facing summary for [playlistName]; the duplicate note appears only when there were any. */
        fun message(playlistName: String): String {
            val base = "Added $added songs to $playlistName"
            return if (duplicates > 0) "$base ($duplicates duplicates skipped)" else base
        }
    }
}
