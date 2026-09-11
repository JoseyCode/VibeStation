package com.boogie.vibestation.views;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Contract tests for VisualizerView mathematical calculations, FFT frequency binning,
 * damping interpolation, and dimension bounds.
 */
public class VisualizerViewTest {

    private static final float EPSILON = 0.0001f;

    /**
     * Verifies Euclidean magnitude computation for vector pairs.
     */
    @Test
    public void testCalculateMagnitude() {
        assertEquals(0.0f, VisualizerView.calculateMagnitude((byte) 0, (byte) 0), EPSILON);
        assertEquals(5.0f, VisualizerView.calculateMagnitude((byte) 3, (byte) 4), EPSILON);
        assertEquals(5.0f, VisualizerView.calculateMagnitude((byte) -3, (byte) -4), EPSILON);
        assertEquals(10.0f, VisualizerView.calculateMagnitude((byte) 10, (byte) 0), EPSILON);
    }

    /**
     * Verifies magnitude retrieval from interleaved FFT byte stream and boundary defenses.
     */
    @Test
    public void testGetMagnitude() {
        byte[] bytes = new byte[]{0, 0, 3, 4, 6, 8};

        // Bin 0: (0, 0) -> 0.0
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, 0), EPSILON);
        // Bin 1: (3, 4) -> 5.0
        assertEquals(5.0f, VisualizerView.getMagnitude(bytes, 1), EPSILON);
        // Bin 2: (6, 8) -> 10.0
        assertEquals(10.0f, VisualizerView.getMagnitude(bytes, 2), EPSILON);

        // Out of bounds and null handling
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, -1), EPSILON);
        assertEquals(0.0f, VisualizerView.getMagnitude(bytes, 10), EPSILON);
        assertEquals(0.0f, VisualizerView.getMagnitude(null, 1), EPSILON);
    }

    /**
     * Verifies frequency band extraction returns zeroed array when given invalid or empty inputs.
     */
    @Test
    public void testExtractFrequencyBandsEmptyOrNull() {
        float[] nullResult = VisualizerView.extractFrequencyBands(null);
        assertNotNull(nullResult);
        assertEquals(3, nullResult.length);
        assertEquals(0f, nullResult[0], EPSILON);
        assertEquals(0f, nullResult[1], EPSILON);
        assertEquals(0f, nullResult[2], EPSILON);

        float[] emptyResult = VisualizerView.extractFrequencyBands(new byte[0]);
        assertEquals(0f, emptyResult[0], EPSILON);
        assertEquals(0f, emptyResult[1], EPSILON);
        assertEquals(0f, emptyResult[2], EPSILON);
    }

    /**
     * Verifies FFT bins are partitioned and normalized correctly into Bass, Mid, and High ranges.
     */
    @Test
    public void testExtractFrequencyBandsSegregation() {
        // 140 bytes = 70 FFT bins
        byte[] bytes = new byte[140];

        // Place peak in bass band (bin 2): magnitude 64
        bytes[4] = 64;
        bytes[5] = 0;

        // Place peak in mid band (bin 20): magnitude 32
        bytes[40] = 32;
        bytes[41] = 0;

        // Place peak in high band (bin 65): magnitude 16
        bytes[130] = 16;
        bytes[131] = 0;

        float[] bands = VisualizerView.extractFrequencyBands(bytes);
        assertEquals(64f / 128f, bands[0], EPSILON);
        assertEquals(32f / 128f, bands[1], EPSILON);
        assertEquals(16f / 128f, bands[2], EPSILON);
    }

    /**
     * Verifies exponential damping filter computes smooth interpolated transition.
     */
    @Test
    public void testApplyDamping() {
        float initial = 0.0f;
        float target = 1.0f;
        float factor = 0.25f;

        float step1 = VisualizerView.applyDamping(initial, target, factor);
        assertEquals(0.25f, step1, EPSILON);

        float step2 = VisualizerView.applyDamping(step1, target, factor);
        assertEquals(0.4375f, step2, EPSILON);
    }

    /**
     * Verifies wave excursion calculations respect orientation boundary formulas.
     */
    @Test
    public void testCalculateMaxAllowedWidth() {
        float width = 1000f;
        float height = 500f;

        // Portrait: width * 0.18f
        assertEquals(180f, VisualizerView.calculateMaxAllowedWidth(false, width, height), EPSILON);

        // Landscape: height * 0.95f
        assertEquals(475f, VisualizerView.calculateMaxAllowedWidth(true, width, height), EPSILON);
    }

    /**
     * Verifies visualizer algorithm constants match system tuning standards.
     */
    @Test
    public void testConstants() {
        assertEquals(32, VisualizerView.RENDER_BINS);
        assertEquals(30, VisualizerView.WAVE_ALPHA);
        assertEquals(0.25f, VisualizerView.DAMPING_FACTOR, EPSILON);
        assertEquals(6, VisualizerView.BASS_BIN_END);
        assertEquals(60, VisualizerView.MID_BIN_END);
        assertEquals(128f, VisualizerView.FFT_MAGNITUDE_DIVISOR, EPSILON);
    }
}
