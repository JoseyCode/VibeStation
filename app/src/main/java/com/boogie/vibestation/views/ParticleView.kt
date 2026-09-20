package com.boogie.vibestation.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import java.util.Collections
import java.util.Random

/**
 * Particle effect view animating floating dots matching the dynamic UI accent color.
 */
class ParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val particleList = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val random = Random()
    private var animator: ValueAnimator? = null

    /** ARGB color applied to every particle when drawn. */
    var particleColor: Int = DEFAULT_COLOR

    /** Unmodifiable view of active particles. */
    val particles: List<Particle>
        get() = Collections.unmodifiableList(particleList)

    /**
     * Data holder representing state, geometry, and motion vectors for an individual particle.
     */
    class Particle(
        var x: Float = 0f,
        var y: Float = 0f,
        var radius: Float = 0f,
        var speed: Float = 0f,
        var alpha: Float = 0f
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        particleList.clear()
        repeat(DEFAULT_PARTICLE_COUNT) {
            particleList.add(createRandomParticle(w.toFloat(), h.toFloat(), random))
        }
        startAnimator()
    }

    /**
     * Initializes and starts the infinite particle animation loop.
     */
    private fun startAnimator() {
        val existing = animator
        if (existing == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIMATION_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val viewHeight = height.toFloat()
                    val viewWidth = width.toFloat()
                    for (p in particleList) {
                        updateParticlePosition(p, viewHeight, viewWidth, random)
                    }
                    invalidate()
                }
                start()
            }
        } else if (!existing.isStarted) {
            existing.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (p in particleList) {
            paint.color = particleColor
            paint.alpha = p.alpha.toInt()
            canvas.drawCircle(p.x, p.y, p.radius, paint)
        }
    }

    /** Resumes animation loop if view is re-attached to the active window hierarchy. */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (width > 0 && height > 0) {
            startAnimator()
        }
    }

    /** Terminates infinite ValueAnimator upon window detachment to prevent Choreographer leaks. */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    companion object {
        const val DEFAULT_PARTICLE_COUNT = 40
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
        const val ANIMATION_DURATION_MS = 1000L

        /**
         * Advances particle position vertically and recycles it to the bottom upon exiting top bounds.
         *
         * @param p          Particle instance to advance.
         * @param viewHeight Total view height.
         * @param viewWidth  Total view width.
         * @param random     Random instance for horizontal respawn.
         */
        fun updateParticlePosition(p: Particle?, viewHeight: Float, viewWidth: Float, random: Random?) {
            if (p == null) return
            p.y -= p.speed
            if (p.y + p.radius < 0) {
                p.y = viewHeight + p.radius
                p.x = if (random != null) random.nextFloat() * viewWidth else 0f
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
        fun createRandomParticle(viewWidth: Float, viewHeight: Float, random: Random): Particle = Particle(
            x = random.nextFloat() * viewWidth,
            y = random.nextFloat() * viewHeight,
            radius = random.nextFloat() * 10f + 5f,
            speed = random.nextFloat() * 2f + 0.5f,
            alpha = random.nextFloat() * 150f + 50f
        )
    }
}
