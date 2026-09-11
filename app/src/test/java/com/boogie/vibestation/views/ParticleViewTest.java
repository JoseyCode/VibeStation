package com.boogie.vibestation.views;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for ParticleView data structures, physics step logic, and constants.
 */
public class ParticleViewTest {

    private static final float EPSILON = 0.0001f;

    /**
     * Verifies Particle constructor correctly stores geometry, speed, and alpha parameters.
     */
    @Test
    public void testParticleInitialization() {
        ParticleView.Particle particle = new ParticleView.Particle(100f, 200f, 8f, 1.5f, 120f);

        assertEquals(100f, particle.x, EPSILON);
        assertEquals(200f, particle.y, EPSILON);
        assertEquals(8f, particle.radius, EPSILON);
        assertEquals(1.5f, particle.speed, EPSILON);
        assertEquals(120f, particle.alpha, EPSILON);
    }

    /**
     * Verifies default constructor instantiates Particle instance cleanly.
     */
    @Test
    public void testDefaultConstructor() {
        ParticleView.Particle particle = new ParticleView.Particle();
        assertNotNull(particle);
        assertEquals(0f, particle.x, EPSILON);
        assertEquals(0f, particle.y, EPSILON);
    }

    /**
     * Verifies upward movement displacement during a single physics update step.
     */
    @Test
    public void testUpdateParticlePositionNormal() {
        ParticleView.Particle particle = new ParticleView.Particle(50f, 100f, 10f, 2.0f, 100f);
        ParticleView.updateParticlePosition(particle, 800f, 400f, new Random(42));

        assertEquals(50f, particle.x, EPSILON);
        assertEquals(98f, particle.y, EPSILON);
    }

    /**
     * Verifies particle resets to the bottom boundary when moving beyond the top edge.
     */
    @Test
    public void testUpdateParticlePositionBoundaryReset() {
        ParticleView.Particle particle = new ParticleView.Particle(50f, 5f, 10f, 20f, 100f);
        float viewHeight = 800f;
        float viewWidth = 400f;

        ParticleView.updateParticlePosition(particle, viewHeight, viewWidth, new Random(42));

        assertEquals(viewHeight + particle.radius, particle.y, EPSILON);
        assertTrue(particle.x >= 0f && particle.x <= viewWidth);
    }

    /**
     * Verifies null particle handling does not raise NullPointerException.
     */
    @Test
    public void testUpdateParticlePositionNullSafety() {
        ParticleView.updateParticlePosition(null, 800f, 400f, new Random());
    }

    /**
     * Verifies randomized particle generation conforms to bounded configuration constraints.
     */
    @Test
    public void testCreateRandomParticle() {
        float width = 500f;
        float height = 1000f;
        Random random = new Random(12345);

        for (int i = 0; i < 50; i++) {
            ParticleView.Particle p = ParticleView.createRandomParticle(width, height, random);
            assertTrue(p.x >= 0f && p.x <= width);
            assertTrue(p.y >= 0f && p.y <= height);
            assertTrue(p.radius >= 5f && p.radius <= 15f);
            assertTrue(p.speed >= 0.5f && p.speed <= 2.5f);
            assertTrue(p.alpha >= 50f && p.alpha <= 200f);
        }
    }

    /**
     * Verifies particle animation and count constants.
     */
    @Test
    public void testConstants() {
        assertEquals(40, ParticleView.DEFAULT_PARTICLE_COUNT);
        assertEquals(0xFFFFFFFF, ParticleView.DEFAULT_COLOR);
        assertEquals(1000L, ParticleView.ANIMATION_DURATION_MS);
    }
}
