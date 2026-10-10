package com.boogie.vibestation

/**
 * Decides when AudioService is in the started state and when it must shut itself down, kept free of
 * Android types so the rules can be unit tested. The service only acts on the answers.
 */
class PlaybackLifecycle {

    /** True while the service is started, so it survives its activity unbinding. */
    var isStarted = false
        private set

    /**
     * Marks the service as started. Returns true only on the transition, so the caller calls
     * startService once per started period and again only after [markStopped].
     */
    fun markStarted(): Boolean {
        if (isStarted) return false
        isStarted = true
        return true
    }

    /** Marks the service as stopped so the next playback start re-enters the started state. */
    fun markStopped() {
        isStarted = false
    }

    /** Swiping the app away stops a paused player but lets a playing one carry on. */
    fun shouldStopOnTaskRemoved(isPlaying: Boolean): Boolean = !isPlaying

    /** A stale notification tap can start a fresh process with nothing loaded; it must not linger. */
    fun shouldStopOnStartCommand(hasSong: Boolean, isPlaying: Boolean): Boolean = !hasSong && !isPlaying

    /** Media-session play only resumes a paused player, so a duplicate play never pauses it. */
    fun shouldToggleOnPlay(isPlaying: Boolean): Boolean = !isPlaying

    /** Media-session pause only pauses a playing player, so a duplicate pause never resumes it. */
    fun shouldToggleOnPause(isPlaying: Boolean): Boolean = isPlaying
}
