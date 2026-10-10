package com.boogie.vibestation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for PlaybackLifecycle: the started-state and shutdown rules AudioService follows so that
 * swiping the app away, the notification Close action and stale notification taps never crash or leave
 * a zombie service behind.
 */
class PlaybackLifecycleTest {

    /**
     * Verifies a new lifecycle is not started, so an idle app leaves no service running.
     */
    @Test
    fun startsStopped() {
        assertFalse(PlaybackLifecycle().isStarted)
    }

    /**
     * Verifies only the first start request reports a transition, so startService is called once.
     */
    @Test
    fun markStartedReportsTransitionOnce() {
        val lifecycle = PlaybackLifecycle()

        assertTrue(lifecycle.markStarted())
        assertTrue(lifecycle.isStarted)
        assertFalse(lifecycle.markStarted())
        assertTrue(lifecycle.isStarted)
    }

    /**
     * Verifies playback can start the service again after a stop (Close, swipe-away, timeout).
     */
    @Test
    fun canRestartAfterStop() {
        val lifecycle = PlaybackLifecycle()
        lifecycle.markStarted()

        lifecycle.markStopped()

        assertFalse(lifecycle.isStarted)
        assertTrue(lifecycle.markStarted())
    }

    /**
     * Verifies stopping is safe to repeat, since Close, the delete intent and the timeout can land in a row.
     */
    @Test
    fun markStoppedIsIdempotent() {
        val lifecycle = PlaybackLifecycle()

        lifecycle.markStopped()
        lifecycle.markStopped()

        assertFalse(lifecycle.isStarted)
    }

    /**
     * Verifies swiping the app away stops a paused player but lets a playing one carry on.
     */
    @Test
    fun taskRemovalStopsOnlyWhenPaused() {
        val lifecycle = PlaybackLifecycle()

        assertTrue(lifecycle.shouldStopOnTaskRemoved(isPlaying = false))
        assertFalse(lifecycle.shouldStopOnTaskRemoved(isPlaying = true))
    }

    /**
     * Verifies a start command with nothing loaded and nothing playing stops the service, but never
     * while a song is loaded or audio is playing.
     */
    @Test
    fun staleStartCommandStopsOnlyWhenIdle() {
        val lifecycle = PlaybackLifecycle()

        assertTrue(lifecycle.shouldStopOnStartCommand(hasSong = false, isPlaying = false))
        assertFalse(lifecycle.shouldStopOnStartCommand(hasSong = true, isPlaying = false))
        assertFalse(lifecycle.shouldStopOnStartCommand(hasSong = false, isPlaying = true))
        assertFalse(lifecycle.shouldStopOnStartCommand(hasSong = true, isPlaying = true))
    }

    /**
     * Verifies media-session play and pause are no-ops when already in the requested state.
     */
    @Test
    fun mediaSessionCommandsOnlyToggleWhenStateDiffers() {
        val lifecycle = PlaybackLifecycle()

        assertTrue(lifecycle.shouldToggleOnPlay(isPlaying = false))
        assertFalse(lifecycle.shouldToggleOnPlay(isPlaying = true))
        assertTrue(lifecycle.shouldToggleOnPause(isPlaying = true))
        assertFalse(lifecycle.shouldToggleOnPause(isPlaying = false))
    }
}
