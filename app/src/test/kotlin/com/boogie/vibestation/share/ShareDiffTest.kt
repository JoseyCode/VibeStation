package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for [ShareDiff] matching between a manifest and the receiver's library. */
class ShareDiffTest {

    private fun remote(title: String, artist: String = "Artist", durationMs: Long = 200_000) =
        ShareTrack(title, artist, "Album", durationMs, 1, "$title.mp3", "audio/mpeg")

    private fun local(id: String, title: String, artist: String = "Artist", durationMs: Long = 200_000) =
        LocalTrack(id, title, artist, durationMs)

    /** Case, whitespace and punctuation differences do not make a song look new. */
    @Test
    fun matchesNormalizedTitleAndArtist() {
        val result = ShareDiff.match(
            listOf(remote("Don't Stop!", "The Band")),
            listOf(local("9", " dont stop ", "the band"))
        )
        assertEquals("9", result.single()?.songId)
    }

    /** A live version with a very different length is a different recording, so it is still missing. */
    @Test
    fun differentDurationIsNotAMatch() {
        val tracks = listOf(remote("Song", durationMs = 300_000))
        val library = listOf(local("1", "Song", durationMs = 200_000))
        assertEquals(listOf(0), ShareDiff.missingIndices(tracks, library))
    }

    /** Durations off by up to the tolerance still match; one millisecond over does not. */
    @Test
    fun toleranceBoundary() {
        val library = listOf(local("1", "Song", durationMs = 200_000))
        val edge = ShareDiff.DURATION_TOLERANCE_MS
        assertEquals("1", ShareDiff.match(listOf(remote("Song", durationMs = 200_000 + edge)), library)[0]?.songId)
        assertNull(ShareDiff.match(listOf(remote("Song", durationMs = 200_000 + edge + 1)), library)[0])
    }

    /** With several candidates the closest length wins. */
    @Test
    fun picksClosestDuration() {
        val library = listOf(local("far", "Song", durationMs = 200_900), local("near", "Song", durationMs = 200_100))
        assertEquals("near", ShareDiff.match(listOf(remote("Song")), library)[0]?.songId)
    }

    /** An unknown duration on either side falls back to the key alone. */
    @Test
    fun unknownDurationMatchesOnKey() {
        assertEquals("1", ShareDiff.match(listOf(remote("Song", durationMs = 0)), listOf(local("1", "Song")))[0]?.songId)
        assertEquals("1", ShareDiff.match(listOf(remote("Song")), listOf(local("1", "Song", durationMs = 0)))[0]?.songId)
    }

    /** The key includes the artist, so the same title by another artist is new. */
    @Test
    fun differentArtistIsMissing() {
        assertEquals(listOf(0), ShareDiff.missingIndices(listOf(remote("Song", "A")), listOf(local("1", "Song", "B"))))
    }

    /** Missing indices keep manifest order and skip owned tracks; an empty library misses everything. */
    @Test
    fun missingIndicesKeepOrder() {
        val tracks = listOf(remote("A"), remote("B"), remote("C"))
        assertEquals(listOf(0, 2), ShareDiff.missingIndices(tracks, listOf(local("2", "B"))))
        assertEquals(listOf(0, 1, 2), ShareDiff.missingIndices(tracks, emptyList()))
    }
}
