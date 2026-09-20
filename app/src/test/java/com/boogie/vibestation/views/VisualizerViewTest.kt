package com.boogie.vibestation.views

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Contract tests for VisualizerView mathematical calculations, FFT frequency binning,
 * damping interpolation, and dimension bounds.
 */
class VisualizerViewTest {

    private companion object {
        const val EPSILON = 0.0001f
    }

    /**
     * Verifies Euclidean magnitude computation for vector pairs.
     */
    @Test
    fun calculateMagnitude() {
        assertEquals(0.0f, VisualizerView.calculateMagnitude(0, 0), EPSILON)
        assertEquals(5.0f, VisualizerView.calculateMagnitude(3, 4), EPSILON)
        assertEquals(5.0f, VisualizerView.calculateMagnitude(-3, -4), EPSILON)
        assertEquals(10.0f, VisualizerView.calculateMagnitude(10, 0), EPSILON)
    }

    /**
     * Verifies magnitude retrieval from interleaved FFT byte stream and boundary defenses.
     */
    @Test
    fun getMagnitude() {
        val bytes = byteArrayOf(0, 0, 3, 4, 6, 8)

        // Bin 0: (0, 0) -> 0.0
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, 0), EPSILON)
        // Bin 1: (3, 4) -> 5.0
        assertEquals(5.0f, VisualizerView.getMagnitude(bytes, 1), EPSILON)
        // Bin 2: (6, 8) -> 10.0
        assertEquals(10.0f, VisualizerView.getMagnitude(bytes, 2), EPSILON)

        // Out of bounds and null handling
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, -1), EPSILON)
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, 10), EPSILON)
        assertEquals(0.0f, VisualizerView.getMagnitude(null, 1), EPSILON)
    }

    /**
     * Verifies frequency band extraction returns zeroed array when given invalid or empty inputs.
     */
    @Test
    fun extractFrequencyBandsEmptyOrNull() {
        assertContentEquals(floatArrayOf(0f, 0f, 0f), VisualizerView.extractFrequencyBands(null))
        assertContentEquals(floatArrayOf(0f, 0f, 0f), VisualizerView.extractFrequencyBands(ByteArray(0)))
    }

    /**
     * Verifies FFT bins are partitioned and normalized correctly into Bass, Mid, and High ranges.
     */
    @Test
    fun extractFrequencyBandsSegregation() {
        // 140 bytes = 70 FFT bins
        val bytes = ByteArray(140)

        // Place peak in bass band (bin 2): magnitude 64
        bytes[4] = 64
        bytes[5] = 0

        // Place peak in mid band (bin 20): magnitude 32
        bytes[40] = 32
        bytes[41] = 0

        // Place peak in high band (bin 65): magnitude 16
        bytes[130] = 16
        bytes[131] = 0

        val bands = VisualizerView.extractFrequencyBands(bytes)
        assertEquals(64f / 128f, bands[0], EPSILON)
        assertEquals(32f / 128f, bands[1], EPSILON)
        assertEquals(16f / 128f, bands[2], EPSILON)
    }

    /**
     * Verifies exponential damping filter computes smooth interpolated transition.
     */
    @Test
    fun applyDamping() {
        val initial = 0.0f
        val target = 1.0f
        val factor = 0.25f

        val step1 = VisualizerView.applyDamping(initial, target, factor)
        assertEquals(0.25f, step1, EPSILON)

        val step2 = VisualizerView.applyDamping(step1, target, factor)
        assertEquals(0.4375f, step2, EPSILON)
    }

    /**
     * Verifies wave excursion calculations respect orientation boundary formulas.
     */
    @Test
    fun calculateMaxAllowedWidth() {
        val width = 1000f
        val height = 500f

        // Portrait: width * 0.18f
        assertEquals(180f, VisualizerView.calculateMaxAllowedWidth(false, width, height), EPSILON)

        // Landscape: height * 0.95f
        assertEquals(475f, VisualizerView.calculateMaxAllowedWidth(true, width, height), EPSILON)
    }

    /**
     * Verifies visualizer algorithm constants match system tuning standards.
     */
    @Test
    fun constants() {
        assertEquals(32, VisualizerView.RENDER_BINS)
        assertEquals(30, VisualizerView.WAVE_ALPHA)
        assertEquals(0.25f, VisualizerView.DAMPING_FACTOR, EPSILON)
        assertEquals(6, VisualizerView.BASS_BIN_END)
        assertEquals(60, VisualizerView.MID_BIN_END)
        assertEquals(128f, VisualizerView.FFT_MAGNITUDE_DIVISOR, EPSILON)
    }
}
