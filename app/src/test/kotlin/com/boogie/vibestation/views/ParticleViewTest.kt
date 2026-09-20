package com.boogie.vibestation.views

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for ParticleView data structures, physics step logic, and constants.
 */
class ParticleViewTest {

    private companion object {
        const val EPSILON = 0.0001f
    }

    /**
     * Verifies Particle constructor correctly stores geometry, speed, and alpha parameters.
     */
    @Test
    fun particleInitialization() {
        val particle = ParticleView.Particle(100f, 200f, 8f, 1.5f, 120f)

        assertEquals(100f, particle.x, EPSILON)
        assertEquals(200f, particle.y, EPSILON)
        assertEquals(8f, particle.radius, EPSILON)
        assertEquals(1.5f, particle.speed, EPSILON)
        assertEquals(120f, particle.alpha, EPSILON)
    }

    /**
     * Verifies the default constructor zero-initializes every field.
     */
    @Test
    fun defaultConstructor() {
        val particle = ParticleView.Particle()

        assertEquals(0f, particle.x, EPSILON)
        assertEquals(0f, particle.y, EPSILON)
        assertEquals(0f, particle.radius, EPSILON)
        assertEquals(0f, particle.speed, EPSILON)
        assertEquals(0f, particle.alpha, EPSILON)
    }

    /**
     * Verifies upward movement displacement during a single physics update step.
     */
    @Test
    fun updateParticlePositionNormal() {
        val particle = ParticleView.Particle(50f, 100f, 10f, 2.0f, 100f)
        ParticleView.updateParticlePosition(particle, 800f, 400f, Random(42))

        assertEquals(50f, particle.x, EPSILON)
        assertEquals(98f, particle.y, EPSILON)
    }

    /**
     * Verifies particle resets to the bottom boundary when moving beyond the top edge.
     */
    @Test
    fun updateParticlePositionBoundaryReset() {
        val particle = ParticleView.Particle(50f, 5f, 10f, 20f, 100f)
        val viewHeight = 800f
        val viewWidth = 400f

        ParticleView.updateParticlePosition(particle, viewHeight, viewWidth, Random(42))

        assertEquals(viewHeight + particle.radius, particle.y, EPSILON)
        assertTrue(particle.x in 0f..viewWidth)
    }

    /**
     * Verifies a null particle is ignored and a null random source respawns at x = 0.
     */
    @Test
    fun updateParticlePositionNullSafety() {
        ParticleView.updateParticlePosition(null, 800f, 400f, Random())

        val particle = ParticleView.Particle(50f, 5f, 10f, 20f, 100f)
        ParticleView.updateParticlePosition(particle, 800f, 400f, null)
        assertEquals(0f, particle.x, EPSILON)
        assertEquals(810f, particle.y, EPSILON)
    }

    /**
     * Verifies randomized particle generation conforms to bounded configuration constraints.
     */
    @Test
    fun createRandomParticle() {
        val width = 500f
        val height = 1000f
        val random = Random(12345)

        repeat(50) {
            val p = ParticleView.createRandomParticle(width, height, random)
            assertTrue(p.x in 0f..width)
            assertTrue(p.y in 0f..height)
            assertTrue(p.radius in 5f..15f)
            assertTrue(p.speed in 0.5f..2.5f)
            assertTrue(p.alpha in 50f..200f)
        }
    }

    /**
     * Verifies particle animation and count constants.
     */
    @Test
    fun constants() {
        assertEquals(40, ParticleView.DEFAULT_PARTICLE_COUNT)
        assertEquals(0xFFFFFFFF.toInt(), ParticleView.DEFAULT_COLOR)
        assertEquals(1000L, ParticleView.ANIMATION_DURATION_MS)
    }
}
