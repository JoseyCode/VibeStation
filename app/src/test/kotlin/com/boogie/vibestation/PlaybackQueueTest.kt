package com.boogie.vibestation

import com.boogie.vibestation.models.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PlaybackQueue cursor movement, wraparound, and upcoming-song lookup: the navigation rules
 * AudioService relies on for next, previous, and artwork preloading.
 */
class PlaybackQueueTest {

    private fun song(id: String) = Song(id, "Title $id", "Artist", "/m/$id.mp3", "alb", "Album", 1, 0L)

    private fun queueOf(position: Int, vararg ids: String) = PlaybackQueue().apply { set(ids.map(::song), position) }

    private fun PlaybackQueue.currentId() = current?.id

    /**
     * Verifies a new queue has no current song, no upcoming songs, and cannot be moved.
     */
    @Test
    fun emptyQueueIsInert() {
        val queue = PlaybackQueue()

        assertTrue(queue.isEmpty)
        assertNull(queue.current)
        assertTrue(queue.upcoming(3).isEmpty())
        assertFalse(queue.advance())
        assertFalse(queue.retreat())
        assertNull(queue.current)
    }

    /**
     * Verifies set places the cursor on the requested song.
     */
    @Test
    fun setPlacesCursorOnRequestedSong() {
        val queue = queueOf(1, "a", "b", "c")

        assertFalse(queue.isEmpty)
        assertEquals("b", queue.currentId())
    }

    /**
     * Verifies an out-of-range or negative starting position leaves no current song rather than throwing.
     */
    @Test
    fun outOfRangePositionHasNoCurrentSong() {
        assertNull(queueOf(3, "a", "b", "c").current)
        assertNull(queueOf(-1, "a", "b", "c").current)
        assertNull(queueOf(0).current)
    }

    /**
     * Verifies advance steps forward and wraps from the last song to the first.
     */
    @Test
    fun advanceWrapsAtTheEnd() {
        val queue = queueOf(1, "a", "b", "c")

        assertTrue(queue.advance())
        assertEquals("c", queue.currentId())
        assertTrue(queue.advance())
        assertEquals("a", queue.currentId())
    }

    /**
     * Verifies retreat steps back and wraps from the first song to the last.
     */
    @Test
    fun retreatWrapsAtTheStart() {
        val queue = queueOf(1, "a", "b", "c")

        assertTrue(queue.retreat())
        assertEquals("a", queue.currentId())
        assertTrue(queue.retreat())
        assertEquals("c", queue.currentId())
    }

    /**
     * Verifies a single-song queue stays on that song in both directions.
     */
    @Test
    fun singleSongQueueLoopsOnItself() {
        val queue = queueOf(0, "only")

        queue.advance()
        assertEquals("only", queue.currentId())
        queue.retreat()
        assertEquals("only", queue.currentId())
    }

    /**
     * Verifies advancing from an unset cursor enters the queue at the first song.
     */
    @Test
    fun advancingFromUnsetCursorEntersAtTheStart() {
        val queue = queueOf(-1, "a", "b", "c").apply { advance() }

        assertEquals("a", queue.currentId())
    }

    /**
     * Verifies upcoming returns the songs after the cursor in order, wrapping past the end.
     */
    @Test
    fun upcomingWrapsAroundTheEnd() {
        val queue = queueOf(1, "a", "b", "c")

        assertEquals(listOf("c", "a"), queue.upcoming(2).map { it.id })
        assertEquals(listOf("c", "a", "b", "c"), queue.upcoming(4).map { it.id })
    }

    /**
     * Verifies upcoming does not move the cursor, and zero or negative counts return nothing.
     */
    @Test
    fun upcomingIsReadOnlyAndToleratesNonPositiveCounts() {
        val queue = queueOf(0, "a", "b")

        assertTrue(queue.upcoming(0).isEmpty())
        assertTrue(queue.upcoming(-2).isEmpty())
        queue.upcoming(5)
        assertEquals("a", queue.currentId())
    }

    /**
     * Verifies the queue is held by reference: songs the caller appends later are reachable, as
     * AudioService's contract with the UI promises.
     */
    @Test
    fun callerEditsToTheListAreVisible() {
        val songs = mutableListOf(song("a"), song("b"))
        val queue = PlaybackQueue().apply { set(songs, 1) }

        songs.add(song("c"))
        queue.advance()

        assertEquals("c", queue.currentId())
    }

    /**
     * Verifies set replaces the previous queue and cursor entirely.
     */
    @Test
    fun setReplacesPreviousQueue() {
        val queue = queueOf(2, "a", "b", "c")

        queue.set(listOf(song("x"), song("y")), 0)

        assertEquals("x", queue.currentId())
        assertEquals(listOf("y", "x"), queue.upcoming(2).map { it.id })
    }
}
