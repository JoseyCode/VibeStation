package com.boogie.vibestation.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for [ShareMotion]: what each state asks of the waves and how the view glides between them. */
class ShareMotionTest {
    private val playlist = SessionHarness.manifest(ShareKind.PLAYLIST, "a", "b", "c", name = "Road Trip")

    private fun transferring(sending: Boolean, done: Long = 50, total: Long = 200) =
        ShareState.Transferring("Sam", playlist, sending, 1, 3, done, total)

    /** Searching wanders: loose layers, rings going out, nothing locked together. */
    @Test
    fun searchingIsLooseAndPings() {
        val motion = ShareMotion.of(ShareState.Searching())
        assertEquals(0f, motion.coherence)
        assertTrue(motion.ping > 0f)
        assertNull(motion.fill)
        assertEquals(ShareMotion.of(ShareState.Idle), motion)
    }

    /** Connecting pings faster and is livelier than searching, and starts to come together. */
    @Test
    fun connectingIsLivelierThanSearching() {
        val searching = ShareMotion.of(ShareState.Searching())
        val connecting = ShareMotion.of(ShareState.Connecting("Sam"))
        assertTrue(connecting.ping > searching.ping)
        assertTrue(connecting.amplitude > searching.amplitude)
        assertTrue(connecting.coherence > searching.coherence)
    }

    /** Pairing locks the layers into one wave step by step: searching, code shown, code confirmed, connected. */
    @Test
    fun coherenceGrowsAsThePhonesPair() {
        val steps = listOf(
            ShareState.Searching(), ShareState.Verifying("Sam", "1", false),
            ShareState.Verifying("Sam", "1", true), ShareState.Locked("Sam")
        ).map { ShareMotion.of(it).coherence }
        assertEquals(steps.sorted(), steps)
        assertEquals(1f, steps.last())
    }

    /** Waiting for an answer breathes, and an offer that wants an answer is warm. */
    @Test
    fun offersBreathe() {
        assertTrue(ShareMotion.of(ShareState.Offering("Sam", playlist)).breathing > ShareMotion.of(ShareState.Locked("Sam")).breathing)
        val incoming = ShareMotion.of(ShareState.IncomingOffer("Sam", playlist, listOf(0), 1))
        assertEquals(ShareTint.ATTENTION, incoming.tint)
        assertEquals(ShareTint.ACCENT, ShareMotion.of(ShareState.Offering("Sam", playlist)).tint)
    }

    /** A transfer is a progress bar: the fill is the progress, and the ripple flows outward when sending, inward when receiving. */
    @Test
    fun transferFillsAndFlows() {
        val sending = ShareMotion.of(transferring(sending = true))
        assertEquals(0.25f, assertNotNull(sending.fill))
        assertEquals(1f, sending.flow)
        assertEquals(-1f, ShareMotion.of(transferring(sending = false)).flow)
        assertEquals(1f, sending.coherence)
        assertEquals(0f, assertNotNull(ShareMotion.of(transferring(sending = true, done = 0, total = 0)).fill))
    }

    /** Only a transfer fills; everything else leaves the waveform a plain wave. */
    @Test
    fun otherStatesHaveNoFill() {
        for (state in ShareDemo.states) {
            if (state !is ShareState.Transferring) assertNull(ShareMotion.of(state).fill, "$state")
        }
    }

    /** A closed screen flattens and fades, in grey. */
    @Test
    fun closedFadesToAFlatMutedLine() {
        val closed = ShareMotion.of(ShareState.Closed("Connection lost"))
        assertEquals(0f, closed.amplitude)
        assertEquals(0f, closed.visibility)
        assertEquals(ShareTint.MUTED, closed.tint)
        assertEquals(1f, ShareMotion.of(ShareState.Searching()).visibility)
    }

    /** Every demo state asks for numbers the view can draw. */
    @Test
    fun everyStateIsInRange() {
        ShareDemo.states.forEach {
            val m = ShareMotion.of(it)
            assertTrue(m.amplitude in 0f..1f && m.coherence in 0f..1f && m.visibility in 0f..1f, "$it")
            assertTrue(m.breathing in 0f..1f && m.ping in 0f..2f && m.flow in -1f..1f && m.speed > 0f, "$it")
        }
    }

    /** Easing covers the asked share of the gap, and nothing at all or everything at the extremes. */
    @Test
    fun easingBlendsNumbers() {
        val from = ShareMotion.of(ShareState.Searching())
        val to = ShareMotion.of(ShareState.Locked("Sam"))
        assertEquals(from, from.easeToward(to, 0f).copy(tint = from.tint))
        assertEquals(to, from.easeToward(to, 1f))
        val half = from.easeToward(to, 0.5f)
        assertEquals((from.amplitude + to.amplitude) / 2f, half.amplitude, 1e-6f)
        assertEquals(0.5f, half.coherence, 1e-6f)
    }

    /** Repeated easing converges on the target. */
    @Test
    fun easingConverges() {
        val target = ShareMotion.of(ShareState.Locked("Sam"))
        var motion = ShareMotion.of(ShareState.Searching())
        repeat(200) { motion = motion.easeToward(target, ShareMotion.smoothing(1f / 60f)) }
        assertEquals(target.amplitude, motion.amplitude, 1e-3f)
        assertEquals(target.coherence, motion.coherence, 1e-3f)
        assertEquals(target.ping, motion.ping, 1e-3f)
    }

    /** A progress bar grows from empty when a transfer starts, and disappears at once when it ends. */
    @Test
    fun fillGrowsFromEmptyAndEndsAtOnce() {
        val start = ShareMotion.of(ShareState.Locked("Sam"))
        val working = ShareMotion.of(transferring(sending = true, done = 100, total = 200))
        assertEquals(0.25f, assertNotNull(start.easeToward(working, 0.5f).fill), 1e-6f)
        assertNull(working.easeToward(start, 0.5f).fill)
    }

    /** The tint is not blended: it switches to the target's. */
    @Test
    fun tintSwitchesAtOnce() {
        val incoming = ShareMotion.of(ShareState.IncomingOffer("Sam", playlist, listOf(0), 1))
        assertEquals(ShareTint.ATTENTION, ShareMotion.REST.easeToward(incoming, 0.1f).tint)
    }

    /** The glide takes the same time whatever the frame rate: two 120 Hz frames equal one 60 Hz frame. */
    @Test
    fun smoothingIsFrameRateIndependent() {
        val oneAt60 = ShareMotion.smoothing(1f / 60f)
        val twoAt120 = 1f - (1f - ShareMotion.smoothing(1f / 120f)).let { it * it }
        assertEquals(oneAt60, twoAt120, 1e-5f)
        assertEquals(0f, ShareMotion.smoothing(0f))
        assertEquals(0f, ShareMotion.smoothing(-1f))
        assertTrue(ShareMotion.smoothing(10f) < 1.0001f)
    }
}
