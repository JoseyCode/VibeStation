package com.example.retroclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.Arrays;

public class VisualizerView extends View {
    private static final int RENDER_BINS = 40;
    private final float[] targetAmplitudes = new float[RENDER_BINS];
    private final float[] smoothedMagnitudes = new float[RENDER_BINS];
    private final Paint wavePaint = new Paint();
    private final Path wavePath = new Path();
    private static final int WAVE_ALPHA = 70;
    private static final float DAMPING_FACTOR = 0.24f;

    public VisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        wavePaint.setAntiAlias(true);
        wavePaint.setColor(Color.WHITE);
        wavePaint.setStyle(Paint.Style.FILL);
    }

    public void updateVisualizer(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        // Process FFT and group into logarithmic bands on the background data capture thread
        int fftSize = bytes.length / 2;
        if (fftSize <= 0) return;

        float logMin = (float) Math.log(1);
        float logMax = (float) Math.log(fftSize);
        float width = getWidth();
        if (width <= 0) width = 1080f; // Fallback

        synchronized (this) {
            for (int i = 0; i < RENDER_BINS; i++) {
                float pct = (float) i / (RENDER_BINS - 1);
                int startBin = (int) Math.exp(logMin + pct * (logMax - logMin));
                int endBin = (int) Math.exp(logMin + (pct + 1f / RENDER_BINS) * (logMax - logMin));

                if (endBin <= startBin) {
                    endBin = startBin + 1;
                }
                if (endBin > fftSize) {
                    endBin = fftSize;
                }

                float sum = 0;
                int count = 0;
                for (int k = startBin; k < endBin; k++) {
                    int rIdx = k * 2;
                    int iIdx = k * 2 + 1;
                    if (iIdx < bytes.length) {
                        float r = bytes[rIdx];
                        float im = bytes[iIdx];
                        sum += (float) Math.sqrt(r * r + im * im);
                        count++;
                    }
                }
                float magnitude = count > 0 ? (sum / count) : 0;
                float amplitude = (magnitude / 128f) * (width * 0.35f);
                if (amplitude > width * 0.45f) {
                    amplitude = width * 0.45f;
                }
                targetAmplitudes[i] = amplitude;
            }
        }
        postInvalidateOnAnimation();
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

        float[] localTargets = new float[RENDER_BINS];
        synchronized (this) {
            System.arraycopy(targetAmplitudes, 0, localTargets, 0, RENDER_BINS);
        }

        boolean needsMoreFrames = false;
        for (int i = 0; i < RENDER_BINS; i++) {
            float diff = localTargets[i] - smoothedMagnitudes[i];
            if (Math.abs(diff) > 0.5f) {
                smoothedMagnitudes[i] += diff * DAMPING_FACTOR;
                needsMoreFrames = true;
            } else {
                smoothedMagnitudes[i] = localTargets[i];
            }
        }

        wavePath.reset();
        wavePath.moveTo(0, height);
        float barHeight = height / (RENDER_BINS - 1);

        for (int i = 0; i < RENDER_BINS; i++) {
            float currentY = height - (i * barHeight);
            float currentX = smoothedMagnitudes[i];

            if (i == 0) {
                wavePath.lineTo(currentX, currentY);
            } else {
                float previousY = height - ((i - 1) * barHeight);
                float previousX = smoothedMagnitudes[i - 1];
                wavePath.cubicTo(
                        previousX, previousY - barHeight / 2,
                        currentX, currentY + barHeight / 2,
                        currentX, currentY
                );
            }
        }

        wavePath.lineTo(0, 0);
        wavePath.close();
        canvas.drawPath(wavePath, wavePaint);

        if (needsMoreFrames) {
            postInvalidateOnAnimation();
        }
    }
}