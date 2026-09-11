package com.boogie.vibestation.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Particle effect view animating floating dots matching the dynamic UI accent color.
 */
public class ParticleView extends View {

    public static final int DEFAULT_PARTICLE_COUNT = 40;
    public static final int DEFAULT_COLOR = 0xFFFFFFFF;
    public static final long ANIMATION_DURATION_MS = 1000L;

    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private ValueAnimator animator;
    private int particleColor = DEFAULT_COLOR;

    /**
     * Data holder representing state, geometry, and motion vectors for an individual particle.
     */
    public static class Particle {
        public float x;
        public float y;
        public float radius;
        public float speed;
        public float alpha;

        /**
         * Default constructor for particle instances.
         */
        public Particle() {
        }

        /**
         * Constructs a particle with designated geometry, velocity, and opacity.
         *
         * @param x      Horizontal position coordinate.
         * @param y      Vertical position coordinate.
         * @param radius Particle circle radius.
         * @param speed  Upward displacement speed per tick.
         * @param alpha  Alpha opacity value.
         */
        public Particle(float x, float y, float radius, float speed, float alpha) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.speed = speed;
            this.alpha = alpha;
        }
    }

    /**
     * Advances particle position vertically and recycles it to the bottom upon exiting top bounds.
     *
     * @param p          Particle instance to advance.
     * @param viewHeight Total view height.
     * @param viewWidth  Total view width.
     * @param random     Random instance for horizontal respawn.
     */
    public static void updateParticlePosition(Particle p, float viewHeight, float viewWidth, Random random) {
        if (p == null) return;
        p.y -= p.speed;
        if (p.y + p.radius < 0) {
            p.y = viewHeight + p.radius;
            p.x = random != null ? random.nextFloat() * viewWidth : 0f;
        }
    }

    /**
     * Generates a single randomized particle configured within given viewport bounds.
     *
     * @param viewWidth  Total view width.
     * @param viewHeight Total view height.
     * @param random     Random instance.
     * @return Initialized particle.
     */
    public static Particle createRandomParticle(float viewWidth, float viewHeight, Random random) {
        Particle p = new Particle();
        p.x = random.nextFloat() * viewWidth;
        p.y = random.nextFloat() * viewHeight;
        p.radius = random.nextFloat() * 10f + 5f;
        p.speed = random.nextFloat() * 2f + 0.5f;
        p.alpha = random.nextFloat() * 150f + 50f;
        return p;
    }

    /**
     * Initializes ParticleView with context.
     *
     * @param context Application context.
     */
    public ParticleView(Context context) {
        super(context);
        init();
    }

    /**
     * Initializes ParticleView with XML attribute set.
     *
     * @param context Application context.
     * @param attrs   XML attributes.
     */
    public ParticleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Configures paint style for drawing filled circular particles.
     */
    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * Updates active particle color.
     *
     * @param color ARGB color integer.
     */
    public void setParticleColor(int color) {
        this.particleColor = color;
    }

    /**
     * Retrieves active particle color.
     *
     * @return ARGB color integer.
     */
    public int getParticleColor() {
        return particleColor;
    }

    /**
     * Retrieves unmodifiable view of active particles.
     *
     * @return Unmodifiable list of active particles.
     */
    public List<Particle> getParticles() {
        return Collections.unmodifiableList(particles);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        particles.clear();
        for (int i = 0; i < DEFAULT_PARTICLE_COUNT; i++) {
            particles.add(createRandomParticle(w, h, random));
        }

        if (animator == null) {
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(ANIMATION_DURATION_MS);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                int height = getHeight();
                int width = getWidth();
                for (Particle p : particles) {
                    updateParticlePosition(p, height, width, random);
                }
                invalidate();
            });
            animator.start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Particle p : particles) {
            paint.setColor(particleColor);
            paint.setAlpha((int) p.alpha);
            canvas.drawCircle(p.x, p.y, p.radius, paint);
        }
    }

    /**
     * Resumes animation loop if view is re-attached to the active window hierarchy.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator != null && !animator.isStarted()) {
            animator.start();
        }
    }

    /**
     * Terminates infinite ValueAnimator upon window detachment to prevent Choreographer leaks.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
