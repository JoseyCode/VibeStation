package com.example.retroclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

public class VisualizerView extends View {
    private static final int RENDER_BINS = 32;
    
    private float targetBass = 0f;
    private float targetMid = 0f;
    private float targetHigh = 0f;
    
    private float smoothedBass = 0f;
    private float smoothedMid = 0f;
    private float smoothedHigh = 0f;
    
    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private static final int WAVE_ALPHA = 70;
    private static final float DAMPING_FACTOR = 0.25f;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    public void updateVisualizer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        int fftSize = bytes.length / 2;
        if (fftSize <= 0) return;

        float bassMax = 0;
        float midMax = 0;
        float highMax = 0;

        // Bass: bins 1 to 6
        int bassEnd = Math.min(6, fftSize);
        for (int k = 1; k < bassEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > bassMax) bassMax = mag;
        }

        // Mid: bins 6 to 60
        int midEnd = Math.min(60, fftSize);
        for (int k = bassEnd; k < midEnd; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > midMax) midMax = mag;
        }

        // High: bins 60 to fftSize
        for (int k = midEnd; k < fftSize; k++) {
            float mag = getMagnitude(bytes, k);
            if (mag > highMax) highMax = mag;
        }

        synchronized (this) {
            targetBass = bassMax / 128f;
            targetMid = midMax / 128f;
            targetHigh = highMax / 128f;
        }
        postInvalidateOnAnimation();
    }

    private float getMagnitude(byte[] bytes, int k) {
        int rIdx = k * 2;
        int iIdx = k * 2 + 1;
        if (iIdx < bytes.length) {
            float r = bytes[rIdx];
            float im = bytes[iIdx];
            return (float) Math.sqrt(r * r + im * im);
        }
        return 0;
    }

    public void setColor(int color) {
        wavePaint.setColor(color);
        wavePaint.setAlpha(WAVE_ALPHA);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        float localBass, localMid, localHigh;
        synchronized (this) {
            localBass = targetBass;
            localMid = targetMid;
            localHigh = targetHigh;
        }

        smoothedBass += (localBass - smoothedBass) * DAMPING_FACTOR;
        smoothedMid += (localMid - smoothedMid) * DAMPING_FACTOR;
        smoothedHigh += (localHigh - smoothedHigh) * DAMPING_FACTOR;

        boolean needsMoreFrames = false;
        if (Math.abs(localBass - smoothedBass) > 0.005f ||
            Math.abs(localMid - smoothedMid) > 0.005f ||
            Math.abs(localHigh - smoothedHigh) > 0.005f) {
            needsMoreFrames = true;
        }

        float barHeight = height / (RENDER_BINS - 1);
        float timeSec = (float) (System.currentTimeMillis() % 100000) / 1000f;

        float maxAllowedWidth = width * 0.15f;
        float bassAmp = smoothedBass * maxAllowedWidth;
        float midAmp = smoothedMid * maxAllowedWidth;
        float highAmp = smoothedHigh * maxAllowedWidth;

        // Clamp individual amplitudes to visualizer max allowed boundary (18% width)
        float maxBoundary = width * 0.18f;
        if (bassAmp > maxBoundary) bassAmp = maxBoundary;
        if (midAmp > maxBoundary) midAmp = maxBoundary;
        if (highAmp > maxBoundary) highAmp = maxBoundary;

        // 3 overlapping waves
        float[] drawXBass = new float[RENDER_BINS];
        float[] drawXMid = new float[RENDER_BINS];
        float[] drawXHigh = new float[RENDER_BINS];

        for (int i = 0; i < RENDER_BINS; i++) {
            // Whip factor increases from bottom (i = 0) to top (i = RENDER_BINS - 1)
            float whipFactor = 0.15f + 1.15f * ((float) i / (RENDER_BINS - 1));

            // Bass (low frequency): slower, lower phase
            float bassSine = (float) Math.sin(i * 0.15f - timeSec * 2.5f) * (bassAmp * 0.25f + width * 0.005f);
            drawXBass[i] = (bassAmp * 0.8f + bassSine) * whipFactor;
            if (drawXBass[i] < 0) drawXBass[i] = 0;
            if (drawXBass[i] > maxBoundary) drawXBass[i] = maxBoundary;

            // Mid (vocal frequency): moderate speed, medium phase
            float midSine = (float) Math.sin(i * 0.25f - timeSec * 4.5f) * (midAmp * 0.35f + width * 0.008f);
            drawXMid[i] = (midAmp * 0.9f + midSine) * whipFactor;
            if (drawXMid[i] < 0) drawXMid[i] = 0;
            if (drawXMid[i] > maxBoundary) drawXMid[i] = maxBoundary;

            // High (treble frequency): fast speed, high phase
            float highSine = (float) Math.sin(i * 0.4f - timeSec * 7.0f) * (highAmp * 0.45f + width * 0.012f);
            drawXHigh[i] = (highAmp * 1.0f + highSine) * whipFactor;
            if (drawXHigh[i] < 0) drawXHigh[i] = 0;
            if (drawXHigh[i] > maxBoundary) drawXHigh[i] = maxBoundary;
        }

        // Draw Layer 1: Bass Wave (Deep background layer, lowest opacity)
        drawSplineWave(canvas, drawXBass, (int) (WAVE_ALPHA * 0.5f), barHeight, height);

        // Draw Layer 2: Mid Wave (Middle layer, medium opacity)
        drawSplineWave(canvas, drawXMid, (int) (WAVE_ALPHA * 0.75f), barHeight, height);

        // Draw Layer 3: High Wave (Foreground layer, highest opacity)
        drawSplineWave(canvas, drawXHigh, WAVE_ALPHA, barHeight, height);

        if (needsMoreFrames || bassAmp > 0.1f || midAmp > 0.1f || highAmp > 0.1f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawSplineWave(Canvas canvas, float[] drawX, int alpha, float barHeight, float height) {
        wavePath.reset();
        wavePath.moveTo(0, height);
        for (int i = 0; i < RENDER_BINS; i++) {
            float currentY = height - (i * barHeight);
            float currentX = drawX[i];
            if (i == 0) {
                wavePath.lineTo(currentX, currentY);
            } else {
                float x1 = drawX[i - 1];
                float y1 = height - (i - 1) * barHeight;
                float x2 = drawX[i];
                float y2 = height - i * barHeight;
                float x0 = (i < 2) ? x1 : drawX[i - 2];
                float x3 = (i >= RENDER_BINS - 1) ? x2 : drawX[i + 1];
                wavePath.cubicTo(
                        x1 + (x2 - x0) / 6f, y1 - barHeight / 3f,
                        x2 - (x3 - x1) / 6f, y2 + barHeight / 3f,
                        x2, y2
                );
            }
        }
        wavePath.lineTo(0, 0);
        wavePath.close();
        wavePaint.setAlpha(alpha);
        canvas.drawPath(wavePath, wavePaint);
    }
}