package com.boogie.vibestation.share

import android.content.ContentResolver
import com.boogie.vibestation.models.Album
import com.boogie.vibestation.models.Playlist
import com.boogie.vibestation.models.Song
import com.boogie.vibestation.util.MusicLibraryUtil
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareChoiceTest {
    private val resolver = mockk<ContentResolver>()
    private val song = Song("1", "Neon Rain", "The Examples", null, "a", "Night Roads", 1, 0)
    private val library = MusicLibraryUtil.Library(
        listOf(song),
        listOf(Album("a", "Night Roads", "The Examples", 0)),
        listOf(Playlist("Road Trip", null))
    )

    private fun labels(kind: ShareKind) = ShareChoice.of(kind, library, resolver).map(ShareChoice::label)

    @Test
    fun songsAreLabelledWithTitleAndArtist() {
        assertEquals(listOf("Neon Rain - The Examples"), labels(ShareKind.SONG))
    }

    @Test
    fun albumsAndPlaylistsAreLabelledWithTheirNames() {
        assertEquals(listOf("Night Roads"), labels(ShareKind.ALBUM))
        assertEquals(listOf("Road Trip"), labels(ShareKind.PLAYLIST))
    }
}
