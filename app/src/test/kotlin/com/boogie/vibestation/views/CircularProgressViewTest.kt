package com.boogie.vibestation.views

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests for CircularProgressView mathematical helpers, bounds clamping, and constants.
 */
class CircularProgressViewTest {

    private companion object {
        const val EPSILON = 0.0001f
    }

    /**
     * Verifies progress within normal [0.0, 1.0] range remains unadjusted.
     */
    @Test
    fun clampProgressNormal() {
        assertEquals(0.0f, CircularProgressView.clampProgress(0.0f), EPSILON)
        assertEquals(0.5f, CircularProgressView.clampProgress(0.5f), EPSILON)
        assertEquals(1.0f, CircularProgressView.clampProgress(1.0f), EPSILON)
    }

    /**
     * Verifies negative and excessive progress values clamp strictly to 0.0 and 1.0 bounds.
     */
    @Test
    fun clampProgressOutOfBounds() {
        assertEquals(0.0f, CircularProgressView.clampProgress(-0.25f), EPSILON)
        assertEquals(0.0f, CircularProgressView.clampProgress(-10.0f), EPSILON)
        assertEquals(1.0f, CircularProgressView.clampProgress(1.25f), EPSILON)
        assertEquals(1.0f, CircularProgressView.clampProgress(99.0f), EPSILON)
    }

    /**
     * Verifies sweep angle degree conversion across standard normalized completion ratios.
     */
    @Test
    fun calculateSweepAngleNormal() {
        assertEquals(0.0f, CircularProgressView.calculateSweepAngle(0.0f), EPSILON)
        assertEquals(90.0f, CircularProgressView.calculateSweepAngle(0.25f), EPSILON)
        assertEquals(180.0f, CircularProgressView.calculateSweepAngle(0.5f), EPSILON)
        assertEquals(270.0f, CircularProgressView.calculateSweepAngle(0.75f), EPSILON)
        assertEquals(360.0f, CircularProgressView.calculateSweepAngle(1.0f), EPSILON)
    }

    /**
     * Verifies sweep angle clamping when given out-of-range progress values.
     */
    @Test
    fun calculateSweepAngleOutOfBounds() {
        assertEquals(0.0f, CircularProgressView.calculateSweepAngle(-1.0f), EPSILON)
        assertEquals(360.0f, CircularProgressView.calculateSweepAngle(2.5f), EPSILON)
    }

    /**
     * Verifies public design constants match UI specifications.
     */
    @Test
    fun constants() {
        assertEquals(12f, CircularProgressView.DEFAULT_STROKE_WIDTH, EPSILON)
        assertEquals(90f, CircularProgressView.START_ANGLE, EPSILON)
        assertEquals(0xFFFFFFFF.toInt(), CircularProgressView.DEFAULT_COLOR)
    }
}
