package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTimeoutTest {
    private class ManualTimer : ShareTimer {
        val pending = mutableListOf<() -> Unit>()
        var scheduled = 0
        var delays = mutableListOf<Long>()

        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            scheduled++
            delays += delayMs
            pending += task
            return { pending -= task }
        }

        fun fire() {
            pending.toList().also { pending.clear() }.forEach { it() }
        }
    }

    private val timer = ManualTimer()
    private var timeouts = 0
    private val watch = SearchTimeout(180_000, timer) { timeouts++ }

    @Test
    fun searchingStartsOneCountdownOfTheGivenLength() {
        watch.onState(ShareState.Searching())
        assertEquals(listOf(180_000L), timer.delays)
    }

    @Test
    fun timeoutFiresOnceWhenTheTimeIsUp() {
        watch.onState(ShareState.Searching())
        timer.fire()
        timer.fire()
        assertEquals(1, timeouts)
    }

    @Test
    fun noticeChangesWhileSearchingDoNotRestartTheClock() {
        watch.onState(ShareState.Searching())
        watch.onState(ShareState.Searching("Connection lost"))
        assertEquals(1, timer.scheduled)
    }

    @Test
    fun leavingSearchingCancelsTheCountdown() {
        watch.onState(ShareState.Searching())
        watch.onState(ShareState.Connecting("Peer"))
        timer.fire()
        assertEquals(0, timeouts)
    }

    @Test
    fun returningToSearchingStartsAFreshCountdown() {
        watch.onState(ShareState.Searching())
        watch.onState(ShareState.Locked("Peer"))
        watch.onState(ShareState.Searching())
        assertEquals(2, timer.scheduled)
        timer.fire()
        assertEquals(1, timeouts)
    }

    @Test
    fun countdownRestartsAfterItFired() {
        watch.onState(ShareState.Searching())
        timer.fire()
        watch.onState(ShareState.Searching())
        assertEquals(2, timer.scheduled)
    }

    @Test
    fun otherStatesNeverStartACountdown() {
        watch.onState(ShareState.Idle)
        watch.onState(ShareState.Closed(null))
        assertEquals(0, timer.scheduled)
    }

    @Test
    fun stopCancelsTheCountdown() {
        watch.onState(ShareState.Searching())
        watch.stop()
        timer.fire()
        assertEquals(0, timeouts)
    }
}
