package com.boogie.vibestation;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A simple particle effect view that animates floating dots matching the dynamic UI color.
 */
public class ParticleView extends View {
    private final List<Particle> particles = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private ValueAnimator animator;
    private int particleColor = 0xFFFFFFFF;

    private static class Particle {
        float x, y, radius, speed, alpha;
    }

    public ParticleView(Context context) {
        super(context);
        init();
    }

    public ParticleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
    }

    public void setParticleColor(int color) {
        this.particleColor = color;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        particles.clear();
        for (int i = 0; i < 40; i++) {
            Particle p = new Particle();
            p.x = random.nextFloat() * w;
            p.y = random.nextFloat() * h;
            p.radius = random.nextFloat() * 10f + 5f;
            p.speed = random.nextFloat() * 2f + 0.5f;
            p.alpha = random.nextFloat() * 150f + 50f;
            particles.add(p);
        }

        if (animator == null) {
            animator = ValueAnimator.ofFloat(0, 1);
            animator.setDuration(1000);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                for (Particle p : particles) {
                    p.y -= p.speed;
                    if (p.y + p.radius < 0) {
                        p.y = getHeight() + p.radius;
                        p.x = random.nextFloat() * getWidth();
                    }
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
}
