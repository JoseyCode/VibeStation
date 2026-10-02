package com.boogie.vibestation.share

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the wave shapes in [ShareWaves]. */
class ShareWavesTest {

    private val positions = (0..40).map { it / 40f }

    /** The taper is nothing at the edges, full in the middle, and the same on both sides. */
    @Test
    fun taperClosesTheEdges() {
        assertEquals(0f, ShareWaves.taper(0f), 1e-6f)
        assertEquals(0f, ShareWaves.taper(1f), 1e-6f)
        assertEquals(1f, ShareWaves.taper(0.5f), 1e-6f)
        assertEquals(ShareWaves.taper(0.2f), ShareWaves.taper(0.8f), 1e-6f)
        assertEquals(0f, ShareWaves.taper(-3f), 1e-6f)
    }

    /** Thickness stays within 0 to 1 for every layer, place, moment and degree of coherence. */
    @Test
    fun thicknessStaysInRange() {
        for (layer in 0 until ShareWaves.LAYER_COUNT) {
            for (coherence in listOf(0f, 0.4f, 1f)) {
                for (phase in listOf(-7f, 0f, 1.3f, 40f)) {
                    positions.forEach {
                        val t = ShareWaves.thickness(layer, it, phase, coherence)
                        assertTrue(t in 0f..1f, "layer $layer at $it phase $phase coherence $coherence gave $t")
                    }
                }
            }
        }
    }

    /** With full coherence every layer is the same wave, so they stack into one line. */
    @Test
    fun coherentLayersAreIdentical() {
        positions.forEach { across ->
            val first = ShareWaves.thickness(0, across, 2.1f, 1f)
            for (layer in 1 until ShareWaves.LAYER_COUNT) {
                assertEquals(first, ShareWaves.thickness(layer, across, 2.1f, 1f), 1e-6f)
            }
        }
    }

    /** With no coherence the layers differ from one another. */
    @Test
    fun looseLayersDiffer() {
        val values = (0 until ShareWaves.LAYER_COUNT).map { ShareWaves.thickness(it, 0.3f, 1f, 0f) }
        assertTrue(values.distinct().size > 1)
    }

    /** The ripple is mirrored around the middle of the screen. */
    @Test
    fun thicknessIsMirrored() {
        for (layer in 0 until ShareWaves.LAYER_COUNT) {
            assertEquals(
                ShareWaves.thickness(layer, 0.2f, 1.7f, 0.3f),
                ShareWaves.thickness(layer, 0.8f, 1.7f, 0.3f),
                1e-5f
            )
        }
    }

    /** Advancing the phase moves the ripple, and the sign of the phase sets which way it travels. */
    @Test
    fun phaseMovesTheRipple() {
        val now = ShareWaves.thickness(0, 0.3f, 1f, 1f)
        assertTrue(abs(now - ShareWaves.thickness(0, 0.3f, 1.5f, 1f)) > 1e-4f)
        assertTrue(abs(ShareWaves.thickness(0, 0.3f, 1.5f, 1f) - ShareWaves.thickness(0, 0.3f, -1.5f, 1f)) > 1e-4f)
    }

    /** The ribbon is pulled most right under the finger and hardly at all far away. */
    @Test
    fun pullIsABellUnderTheFinger() {
        assertEquals(1f, ShareWaves.pull(0.3f, 0.3f, 0.2f), 1e-6f)
        assertEquals(ShareWaves.pull(0.2f, 0.3f, 0.2f), ShareWaves.pull(0.4f, 0.3f, 0.2f), 1e-6f)
        assertTrue(ShareWaves.pull(0.9f, 0.3f, 0.2f) < 0.001f)
        assertTrue(ShareWaves.pull(0.35f, 0.3f, 0.2f) > ShareWaves.pull(0.5f, 0.3f, 0.2f))
    }

    /** A ping leaves the middle strong and weakens as it nears the edges. */
    @Test
    fun pingTravelsOutAndFades() {
        assertEquals(1f, ShareWaves.ping(0.5f, 0f), 1e-6f)
        assertEquals(0f, ShareWaves.ping(0.5f, 1f), 1e-6f)
        val near = ShareWaves.ping(0.5f + 0.25f, 0.5f)
        val far = ShareWaves.ping(0.5f + 0.45f, 0.9f)
        assertTrue(near > far)
        assertEquals(ShareWaves.ping(0.25f, 0.5f), ShareWaves.ping(0.75f, 0.5f), 1e-6f)
        assertTrue(ShareWaves.ping(0.5f, 0.7f) < 0.01f)
    }

    /** Pings in flight are spread evenly and wrap around to the middle again. */
    @Test
    fun pingTravelWraps() {
        assertEquals(0.25f, ShareWaves.pingTravel(0.25f, 0, 2), 1e-6f)
        assertEquals(0.75f, ShareWaves.pingTravel(0.25f, 1, 2), 1e-6f)
        assertEquals(0.25f, ShareWaves.pingTravel(7.25f, 0, 2), 1e-5f)
        assertEquals(0.25f, ShareWaves.pingTravel(0.75f, 1, 2), 1e-6f)
    }

    /** The pop-in starts at 0, ends at exactly 1, and goes past 1 on the way. */
    @Test
    fun overshootSettlesAtOne() {
        assertEquals(0f, ShareWaves.overshoot(0f), 1e-6f)
        assertEquals(1f, ShareWaves.overshoot(1f), 1e-6f)
        assertEquals(1f, ShareWaves.overshoot(5f), 1e-6f)
        assertEquals(0f, ShareWaves.overshoot(-2f), 1e-6f)
        assertTrue((1..9).map { ShareWaves.overshoot(it / 10f) }.max() > 1f)
    }

    /** The smooth ease runs from 0 to 1 and is slow at the ends and fast in the middle. */
    @Test
    fun smoothstepEasesInAndOut() {
        assertEquals(0f, ShareWaves.smoothstep(0f), 1e-6f)
        assertEquals(1f, ShareWaves.smoothstep(1f), 1e-6f)
        assertEquals(0.5f, ShareWaves.smoothstep(0.5f), 1e-6f)
        assertEquals(0f, ShareWaves.smoothstep(-1f), 1e-6f)
        assertEquals(1f, ShareWaves.smoothstep(2f), 1e-6f)
        assertTrue(ShareWaves.smoothstep(0.1f) < 0.1f)
        assertTrue(ShareWaves.smoothstep(0.9f) > 0.9f)
    }

    /** The ambient colour starts where it is told, goes round at a steady pace, and wraps past a full turn. */
    @Test
    fun hueDriftsRoundTheWheel() {
        assertEquals(120f, ShareWaves.hue(120f, 0f), 1e-4f)
        assertEquals(140f, ShareWaves.hue(120f, 1f), 1e-4f)
        assertEquals(0f, ShareWaves.hue(0f, 360f / ShareWaves.HUE_DEGREES_PER_SECOND), 1e-3f)
        assertEquals(20f, ShareWaves.hue(350f, 1.5f), 1e-3f)
        assertTrue(ShareWaves.hue(10f, -5f) in 0f..360f)
        assertTrue((0..200).all { ShareWaves.hue(300f, it * 0.7f) in 0f..360f })
    }
}
